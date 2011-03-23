package stormpot;

import java.util.concurrent.Semaphore;

public class BasicPool<T extends Poolable> implements Pool<T> {

  private final Allocator<? extends T> allocator;
  private final Semaphore semaphore;

  public BasicPool(Config config, Allocator<? extends T> objectSource) {
    this.allocator = objectSource;
    synchronized (config) {
      this.semaphore = new Semaphore(config.getSize());
    }
  }

  public T claim() {
    semaphore.acquireUninterruptibly();
    return allocator.allocate(null);
  }
}
