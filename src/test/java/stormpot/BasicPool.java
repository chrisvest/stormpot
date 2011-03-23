package stormpot;

import java.util.concurrent.Semaphore;

public class BasicPool<T extends Poolable> implements Pool<T> {

  private final Allocator<? extends T> allocator;
  private final Semaphore semaphore;
  private final Slot slot;

  public BasicPool(Config config, Allocator<? extends T> objectSource) {
    synchronized (config) {
      this.semaphore = new Semaphore(config.getSize());
    }
    this.allocator = objectSource;
    this.slot = new SemaphoreReleasingSlot(semaphore);
  }

  public T claim() {
    semaphore.acquireUninterruptibly();
    return allocator.allocate(slot);
  }
}
