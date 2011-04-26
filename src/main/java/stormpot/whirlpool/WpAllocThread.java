package stormpot.whirlpool;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import stormpot.Allocator;
import stormpot.Completion;
import stormpot.Config;
import stormpot.Poolable;

@SuppressWarnings("unchecked")
class WpAllocThread extends Thread implements Completion {

  private final CountDownLatch shutdownLatch;
  private final int targetSize;
  private final Allocator allocator;
  private final Whirlpool pool;
  
  private volatile boolean runnable = true;
  
  private int size;
  private long relieveTimeout = 10;
  private TimeUnit relieveUnit = TimeUnit.MILLISECONDS;

  public WpAllocThread(Config config, Whirlpool whirlpool) {
    super("Whirlpool-Allocator-Thread for " + config.getAllocator());
    shutdownLatch = new CountDownLatch(1);
    targetSize = config.getSize();
    allocator = config.getAllocator();
    pool = whirlpool;
    if (targetSize < 1) {
      throw new IllegalArgumentException("pool size must be at least 1");
    }
  }

  public void shutdown() {
    runnable = false;
  }

  public void await() throws InterruptedException {
    shutdownLatch.await();
  }

  public boolean await(long timeout, TimeUnit unit) throws InterruptedException {
    return shutdownLatch.await(timeout, unit);
  }

  @Override
  public void run() {
    while (runnable) {
      reallocate();
    }
    // we've been shut down
    deallocateAll();
    shutdownLatch.countDown();
  }

  private void reallocate() {
    if (size < targetSize) {
      WSlot slot = new WSlot(pool);
      allocate(slot);
    }
    WSlot slot;
    try {
      slot = pool.relieve(relieveTimeout, relieveUnit);
      if (slot != null) {
        deallocate(slot);
        allocate(slot);
      }
    } catch (InterruptedException e) {
      // take it as a shutdown signal
      pool.shutdown();
    }
  }

  private void allocate(WSlot slot) {
    if (!runnable) {
      return;
    }
    try {
      slot.obj = allocator.allocate(slot);
      if (slot.obj != null) {
        size++;
      } else {
        slot.poison = new NullPointerException("allocation returned null");
      }
    } catch (Exception e) {
      slot.poison = e;
    }
    slot.created = System.currentTimeMillis();
    pool.release(slot);
  }

  private void deallocate(WSlot slot) {
    try {
      if (slot.obj != null) {
        size--;
        allocator.deallocate((Poolable) slot.obj);
      }
    } catch (Exception e) {
      // ignored as per spec.
    }
    slot.created = 0;
    slot.next = null;
    slot.obj = null;
    slot.poison = null;
    slot.claimed = false;
  }

  private void deallocateAll() {
    while (size > 0) {
      WSlot slot;
      try {
        slot = pool.relieve(relieveTimeout, relieveUnit);
        if (slot == null) {
          continue;
        }
        deallocate(slot);
      } catch (InterruptedException e) {
        // ignore interrupts - we're already trying to shut down
      }
    }
  }
}
