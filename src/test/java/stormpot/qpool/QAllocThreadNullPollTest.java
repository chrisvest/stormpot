package stormpot.qpool;

import java.util.LinkedList;
import java.util.Queue;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;

import org.junit.Test;

import stormpot.Config;
import stormpot.CountingAllocator;
import stormpot.Poolable;

/**
 * This is a rather esoteric case. It turned out that when the QueuePool
 * was resized to become smaller, and the pool was already depleted, then
 * it was possible for the QAllocThread to pull a null from the live queue,
 * and try to deallocate it. This is obviously not good, because it would
 * kill the allocation thread, halting all meaningful function of the pool.
 * 
 * Writing a test for this, as you can probably guess, was not easy.
 * @author Chris Vest &lt;mr.chrisvest@gmail.com&gt;
 */
public class QAllocThreadNullPollTest {
  interface Callable<T> {
    T call();
  }
  
  @SuppressWarnings("serial")
  static class Stop extends RuntimeException {}

  @SuppressWarnings("serial")
  private BlockingQueue<QSlot<Poolable>> callQueue(
      final Queue<Callable<QSlot<Poolable>>> calls) {
    return new LinkedBlockingQueue<QSlot<Poolable>>() {
      QSlot<Poolable> lastValue;
      
      public QSlot<Poolable> poll(long timeout, TimeUnit unit)
          throws InterruptedException {
        Callable<QSlot<Poolable>> callable = calls.poll();
        if (callable != null) {
          lastValue = callable.call();
        }
        return lastValue;
      }

      public boolean offer(QSlot<Poolable> e) {
        return false;
      }
    };
  }

  private Callable<QSlot<Poolable>> ret(final QSlot<Poolable> slot) {
    return new Callable<QSlot<Poolable>>() {
      public QSlot<Poolable> call() {
        return slot;
      }
    };
  }

  private Callable<QSlot<Poolable>> setSizeReturn(
      final QAllocThread<Poolable> th,
      final int size,
      final QSlot<Poolable> slot) {
    return new Callable<QSlot<Poolable>>() {
      public QSlot<Poolable> call() {
        th.setTargetSize(size);
        return slot;
      }
    };
  }

  private Callable<QSlot<Poolable>> throwStop() {
    return new Callable<QSlot<Poolable>>() {
      public QSlot<Poolable> call() {
        throw new Stop();
      }
    };
  }
  
  @Test(timeout = 300) public void
  mustNotDeallocateNullWhenSizeIsReducedAndPoolIsDepleted() {
    Queue<Callable<QSlot<Poolable>>> calls =
        new LinkedList<Callable<QSlot<Poolable>>>();
    @SuppressWarnings({ "unchecked", "rawtypes" })
    BlockingQueue<QSlot<Poolable>> live = callQueue(new LinkedList());
    BlockingQueue<QSlot<Poolable>> dead = callQueue(calls);
    Config<Poolable> config = new Config<Poolable>();
    config.setAllocator(new CountingAllocator());
    config.setSize(2);
    QAllocThread<Poolable> th = new QAllocThread<Poolable>(live, dead, config);
    
    calls.offer(ret(new QSlot<Poolable>(live)));
    calls.offer(ret(new QSlot<Poolable>(live)));
    calls.offer(setSizeReturn(th, 1, null));
    calls.offer(throwStop());
    
    try {
      th.run();
    } catch (Stop _) {
      // we're happy now
    }
  }
}
