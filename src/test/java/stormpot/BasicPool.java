package stormpot;

import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.locks.Condition;
import java.util.concurrent.locks.Lock;
import java.util.concurrent.locks.ReentrantLock;

public class BasicPool<T extends Poolable> implements Pool<T> {

  private final Allocator<? extends T> allocator;
  private final Poolable[] pool;
  private final Slot slot;
  private final AtomicInteger count;
  private final Lock lock;
  private final Condition released;

  public BasicPool(Config config, Allocator<? extends T> objectSource) {
    synchronized (config) {
      this.pool = new Poolable[config.getSize()];
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
}
