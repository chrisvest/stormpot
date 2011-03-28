package stormpot;

import java.util.concurrent.atomic.AtomicInteger;

public class CountingAllocator implements Allocator {
  private final AtomicInteger allocations = new AtomicInteger();
  private final AtomicInteger deallocations = new AtomicInteger();

  public Poolable allocate(Slot slot) {
    allocations.incrementAndGet();
    return new GenericPoolable(slot);
  }

  public void deallocate(Poolable poolable) {
    deallocations.incrementAndGet();
  }

  public void reset() {
    allocations.set(0);
    deallocations.set(0);
  }
  
  public int allocations() {
    return allocations.get();
  }
  
  public int deallocations() {
    return deallocations.get();
  }
}
