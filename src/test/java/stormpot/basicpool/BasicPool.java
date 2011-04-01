package stormpot.basicpool;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.locks.Condition;
import java.util.concurrent.locks.Lock;
import java.util.concurrent.locks.ReentrantLock;

import stormpot.Allocator;
import stormpot.Completion;
import stormpot.Config;
import stormpot.LifecycledPool;
import stormpot.PoolException;
import stormpot.Poolable;
import stormpot.Slot;

/**
 * The BasicPool is a minimal implementation of the Pool interface.
 * It was used to help flesh out the API, and can be considered a
 * reference implementation. It is not in any way optimised. Rather,
 * the implementation has been kept as simple and small as possible.
 * 
 * @author Chris Vest &lt;mr.chrisvest@gmail.com&gt;
 *
 * @param <T>
 */
public class BasicPool<T extends Poolable> implements LifecycledPool<T> {

  private final Allocator<T> allocator;
  private final Poolable[] pool;
  private final BasicSlot[] slots;
  private final AtomicInteger count;
  private final Lock lock;
  private final Condition released;
  private final long ttlMillis;
  private boolean shutdown;

  public BasicPool(Config<T> config) {
    synchronized (config) {
      int size = config.getSize();
      if (size < 1) {
        throw new IllegalArgumentException(
            "size must be at least 1, but was " + size);
      }
      this.pool = new Poolable[size];
      this.slots = new BasicSlot[size];
      this.ttlMillis = config.getTTLUnit().toMillis(config.getTTL());
      this.allocator = config.getAllocator();
    }
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
        try {
          slot = slot(index);
          obj = allocator.allocate(slot);
        } catch (RuntimeException e) {
          throw new PoolException("Failed allocation", e);
        }
        if (obj == null) {
          throw new PoolException("Allocator returned null");
        }
        slots[index] = slot;
        pool[index] = obj;
      }
      slot.claim();
      count.incrementAndGet();
      return (T) obj;
    } finally {
      lock.unlock();
    }
  }


  public T claim(long timeout, TimeUnit unit) {
    return claim();
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

  /**
   * This class is static because we need to be able to create arrays of
   * BasicSlots. If the BasicSlot class was non-static, then it would
   * inherit the generic type parameter from the outer class and would
   * become transitively generic. This is no good, because you cannot make
   * generic arrays.
   * So we make the class static, and pass a reference to the outer
   * BasicPool through a constructor parameter.
   * @author Chris Vest &lt;mr.chrisvest@gmail.com&gt;
   *
   */
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

    private void claim() {
      claimed = true;
    }
    
    private boolean isClaimed() {
      return claimed;
    }

    public void release() {
      bpool.lock.lock();
      if (!claimed) {
        bpool.lock.unlock();
        return;
      }
      if (System.currentTimeMillis() > expires) {
        try {
          bpool.allocator.deallocate(bpool.pool[index]);
        } catch (RuntimeException _) {
          // exceptions from deallocate are ignored as per specification.
        }
        bpool.pool[index] = null;
      }
      claimed = false;
      bpool.count.decrementAndGet();
      bpool.released.signalAll();
      bpool.lock.unlock();
    }
  }

  private final class ShutdownTask extends Thread implements Completion {
    private final CountDownLatch completionLatch;
    
    public ShutdownTask() {
      completionLatch = new CountDownLatch(1);
    }
    
    public void run() {
      lock.lock();
      try {
        for (int index = 0; index < pool.length; index++) {
          if (pool[index] == null) {
            continue;
          }
          while(slots[index].isClaimed()) {
            released.awaitUninterruptibly();
          }
          T poolable = (T) pool[index];
          pool[index] = null;
          try {
            allocator.deallocate(poolable);
          } catch (RuntimeException _) {
            // exceptions from deallocate are ignored as per specification.
          }
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
