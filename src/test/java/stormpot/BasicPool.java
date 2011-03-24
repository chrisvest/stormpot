package stormpot;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.locks.Condition;
import java.util.concurrent.locks.Lock;
import java.util.concurrent.locks.ReentrantLock;

public class BasicPool<T extends Poolable> implements LifecycledPool<T> {

  private final Allocator<? extends T> allocator;
  private final Poolable[] pool;
  private final BasicSlot[] slots;
  private final AtomicInteger count;
  private final Lock lock;
  private final Condition released;
  private final long ttlMillis;
  private boolean shutdown;

  public BasicPool(Config config, Allocator<? extends T> objectSource) {
    synchronized (config) {
      int size = config.getSize();
      if (size < 1) {
        throw new IllegalArgumentException(
            "size must be at least 1, but was " + size);
      }
      this.pool = new Poolable[size];
      this.slots = new BasicSlot[size];
      this.ttlMillis = config.getTTLUnit().toMillis(config.getTTL());
    }
    this.allocator = objectSource;
    this.count = new AtomicInteger();
    this.lock = new ReentrantLock();
    this.released = lock.newCondition();
  }

  public T claim() {
    lock.lock();
    try {
      if (shutdown) {
        throw new IllegalStateException("pool is shut down");
      }
      int index = count.get();
      while (index == pool.length) {
        released.awaitUninterruptibly();
        index = count.get();
      }
      if (shutdown) {
        throw new IllegalStateException("pool is shut down");
      }
      Poolable obj = pool[index];
      BasicSlot slot = slots[index];
      if (obj == null) {
        slot = slots[index] = slot(index);
        obj = pool[index] = allocator.allocate(slot);
      }
      slot.claim();
      count.incrementAndGet();
      return (T) obj;
    } finally {
      lock.unlock();
    }
  }

  private BasicSlot slot(final int index) {
    return new BasicSlot(index, this);
  }

  public Completion shutdown() {
    lock.lock();
    try {
      shutdown = true;
      ShutdownTask shutdownTask = new ShutdownTask();
      shutdownTask.start();
      return shutdownTask;
    } finally {
      lock.unlock();
    }
  }

  private final static class BasicSlot implements Slot {
    private final int index;
    private final long expires;
    private boolean claimed;
    private final BasicPool bpool;

    private BasicSlot(int index, BasicPool bpool) {
      this.index = index;
      this.bpool = bpool;
      this.expires = System.currentTimeMillis() + bpool.ttlMillis;
    }

    public void claim() {
      claimed = true;
    }
    
    public boolean isClaimed() {
      return claimed;
    }

    public void release() {
      bpool.lock.lock();
      if (System.currentTimeMillis() > expires) {
        bpool.allocator.deallocate(bpool.pool[index]);
        bpool.pool[index] = null;
      }
      claimed = false;
      bpool.count.decrementAndGet();
      
      bpool.released.signalAll();
      bpool.lock.unlock();
    }
  }

  public class ShutdownTask extends Thread implements Completion {
    private final CountDownLatch completionLatch;
    
    public ShutdownTask() {
      completionLatch = new CountDownLatch(1);
    }
    
    public void run() {
      lock.lock();
      try {
        for (int index = 0; index < pool.length; index++) {
            if (slots[index] == null) {
              continue;
            }
            while(slots[index].isClaimed()) {
              released.awaitUninterruptibly();
            }
            Poolable poolable = pool[index];
            pool[index] = null;
            allocator.deallocate(poolable);
        }
      } finally {
        completionLatch.countDown();
        lock.unlock();
      }
    }

    public void await() throws InterruptedException {
      completionLatch.await();
    }

    public boolean await(long timeout, TimeUnit unit)
        throws InterruptedException {
      return completionLatch.await(timeout, unit);
    }
  }
}
