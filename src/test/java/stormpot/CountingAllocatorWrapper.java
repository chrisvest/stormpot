package stormpot;

import java.util.concurrent.atomic.AtomicInteger;

public class CountingAllocatorWrapper implements Allocator {

  private final Allocator delegate;
  private final AtomicInteger allocations;
  private final AtomicInteger deallocations;

  public CountingAllocatorWrapper(Allocator delegate) {
    this.delegate = delegate;
    allocations = new AtomicInteger();
    deallocations = new AtomicInteger();
  }

  public Poolable allocate(Slot lease) {
    allocations.incrementAndGet();
    return delegate.allocate(lease);
  }
  
  public int countAllocations() {
    return allocations.get();
  }

  public int countDeallocations() {
    return deallocations.get();
  }

  public void deallocate(Poolable poolable) {
    deallocations.incrementAndGet();
    delegate.deallocate(poolable);
  }
}
