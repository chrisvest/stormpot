package stormpot;

import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.locks.Condition;
import java.util.concurrent.locks.Lock;
import java.util.concurrent.locks.ReentrantLock;

public class BasicPool<T extends Poolable> implements LifecycledPool<T> {

  private final Allocator<? extends T> allocator;
  private final Poolable[] pool;
  private final Slot slot;
  private final AtomicInteger count;
  private final Lock lock;
  private final Condition released;
  private boolean shutdown;

  public BasicPool(Config config, Allocator<? extends T> objectSource) {
    synchronized (config) {
      int size = config.getSize();
      if (size < 1) {
        throw new IllegalArgumentException(
            "size must be at least 1, but was " + size);
      }
      this.pool = new Poolable[size];
    }
    this.allocator = objectSource;
    this.count = new AtomicInteger();
    this.lock = new ReentrantLock();
    this.released = lock.newCondition();
    this.slot = new Slot() {
      public void release() {
        lock.lock();
        count.decrementAndGet();
        released.signal();
        lock.unlock();
      }
    };
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
      Poolable obj = pool[index];
      if (obj == null) {
        obj = pool[index] = allocator.allocate(slot);
      }
      count.incrementAndGet();
      return (T) obj;
    } finally {
      lock.unlock();
    }
  }

  public void shutdown() {
    lock.lock();
    shutdown = true;
    lock.unlock();
  }
}
