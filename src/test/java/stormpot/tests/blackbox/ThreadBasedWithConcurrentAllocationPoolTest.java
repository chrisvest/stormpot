package stormpot.tests.blackbox;

import stormpot.Allocator;
import stormpot.Pool;
import stormpot.PoolBuilder;
import stormpot.Poolable;

public class ThreadBasedWithConcurrentAllocationPoolTest extends AllocatorBasedPoolTest {
  @Override
  protected <T extends Poolable> PoolBuilder<T> createInitialPoolBuilder(Allocator<T> allocator) {
    return Pool.fromThreaded(allocator).setMaxConcurrentAllocations(2);
  }
}
