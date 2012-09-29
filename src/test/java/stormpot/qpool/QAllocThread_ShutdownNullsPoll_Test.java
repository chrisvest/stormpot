package stormpot.qpool;

import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;

import org.junit.Before;
import org.junit.Test;

import stormpot.Config;
import stormpot.CountingAllocator;
import stormpot.Poolable;

/**
 * In this test, we make sure that the shut down process takes precautions
 * against the possibility that it might poll a null from the dead queue.
 * @author Chris Vest &lt;mr.chrisvest@gmail.com&gt;
 */
public class QAllocThread_ShutdownNullsPoll_Test {
  Config<Poolable> config;
  
  @Before public void
  setUp() {
    config = new Config<Poolable>();
    config.setAllocator(new CountingAllocator());
    config.setSize(2);
  }

  @SuppressWarnings("serial")
  @Test(timeout = 300) public void
  mustHandleDeadNullsInShutdown() throws InterruptedException {
    BlockingQueue<QSlot<Poolable>> live = new LinkedBlockingQueue<QSlot<Poolable>>() {
      public boolean offer(QSlot<Poolable> e) {
        Thread.currentThread().interrupt();
        return super.offer(e);
      }
    };
    BlockingQueue<QSlot<Poolable>> dead = new LinkedBlockingQueue<QSlot<Poolable>>();
    QAllocThread<Poolable> thread =
        new QAllocThread<Poolable>(live, dead, config);
    thread.run();
    // must complete before test times out, and not throw NPE
  }

  @SuppressWarnings("serial")
  @Test(timeout = 300) public void
  mustHandleLiveNullsInShutdown() throws InterruptedException {
    BlockingQueue<QSlot<Poolable>> live = new LinkedBlockingQueue<QSlot<Poolable>>() {
      public boolean offer(QSlot<Poolable> e) {
        Thread.currentThread().interrupt();
        return true;
      }
    };
    BlockingQueue<QSlot<Poolable>> dead = new LinkedBlockingQueue<QSlot<Poolable>>();
    dead.add(new QSlot<Poolable>(live));
    QAllocThread<Poolable> thread =
        new QAllocThread<Poolable>(live, dead, config);
    thread.run();
    // must complete before test times out, and not throw NPE
  }
}
