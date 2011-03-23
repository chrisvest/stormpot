package stormpot;

import java.util.concurrent.atomic.AtomicInteger;

public class CountingAllocatorWrapper implements Allocator {

  private final Allocator delegate;
  private final AtomicInteger counter;

  public CountingAllocatorWrapper(Allocator delegate) {
    this.delegate = delegate;
    counter = new AtomicInteger();
  }

  public Poolable allocate(Slot lease) {
    counter.incrementAndGet();
    return delegate.allocate(lease);
  }
  
  public int countAllocations() {
    return counter.get();
  }
}
