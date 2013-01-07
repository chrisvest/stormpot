package stormpot;

import java.util.LinkedList;
import java.util.Queue;
import java.util.concurrent.BlockingQueue;

import org.junit.Test;

/**
 * This is a rather esoteric case. It turned out that when a queue-based Pool
 * was resized to become smaller, and the pool was already depleted, then
 * it was possible for the *AllocThread to pull a null from the live queue,
 * and try to deallocate it. This is obviously not good, because it would
 * kill the allocation thread, halting all meaningful function of the pool.
 * 
 * Writing a test for this, as you can probably guess, was not easy.
 * @author Chris Vest &lt;mr.chrisvest@gmail.com&gt;
 */
public abstract class AllocThread_NullPollFromLiveWhileShrinking_TestTemplate<
  SLOT,
  ALLOC_THREAD extends Thread> {
  
  @SuppressWarnings("serial")
  static class Stop extends RuntimeException {}

  private BlockingQueue<SLOT> callQueue(
      final Queue<Callable<SLOT>> calls) {
    return new PrimedBlockingQueue<SLOT>(calls);
  }

  private Callable<SLOT> ret(final SLOT slot) {
    return new Callable<SLOT>() {
      public SLOT call() {
        return slot;
      }
    };
  }

  private Callable<SLOT> setSizeReturn(
      final ALLOC_THREAD th,
      final int size,
      final SLOT slot) {
    return new Callable<SLOT>() {
      public SLOT call() {
        setTargetSize(th, size);
        return slot;
      }
    };
  }

  private Callable<SLOT> throwStop() {
    return new Callable<SLOT>() {
      public SLOT call() {
        throw new Stop();
      }
    };
  }
  
  @Test(timeout = 300) public void
  mustNotDeallocateNullWhenSizeIsReducedAndPoolIsDepleted() {
    Queue<Callable<SLOT>> calls =
        new LinkedList<Callable<SLOT>>();
    @SuppressWarnings({ "unchecked", "rawtypes" })
    BlockingQueue<SLOT> live = callQueue(new LinkedList());
    BlockingQueue<SLOT> dead = callQueue(calls);
    Config<Poolable> config = new Config<Poolable>();
    config.setAllocator(new CountingAllocator());
    config.setSize(2);
    ALLOC_THREAD th = createAllocThread(live, dead, config);
    
    calls.offer(ret(createSlot(live)));
    calls.offer(ret(createSlot(live)));
    calls.offer(setSizeReturn(th, 1, null));
    calls.offer(throwStop());
    
    try {
      th.run();
    } catch (Stop _) {
      // we're happy now
    }
  }
  
  protected abstract SLOT createSlot(BlockingQueue<SLOT> live);

  protected abstract ALLOC_THREAD createAllocThread(
      BlockingQueue<SLOT> live,
      BlockingQueue<SLOT> dead,
      Config<Poolable> config);

  protected abstract void setTargetSize(
      final ALLOC_THREAD thread,
      final int size);
}
