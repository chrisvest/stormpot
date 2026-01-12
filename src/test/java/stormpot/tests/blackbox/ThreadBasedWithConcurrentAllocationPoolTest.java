package stormpot.tests.blackbox;

import org.junit.jupiter.api.Disabled;
import stormpot.Allocator;
import stormpot.Pool;
import stormpot.PoolBuilder;
import stormpot.Poolable;

public class ThreadBasedWithConcurrentAllocationPoolTest extends AllocatorBasedPoolTest {
  @Override
  protected <T extends Poolable> PoolBuilder<T> createInitialPoolBuilder(Allocator<T> allocator) {
    return Pool.fromThreaded(allocator).setMaxConcurrentAllocations(2);
  }

  @Disabled("This causes issues with leak detection where reference processing appear to be indefinitely delayed " +
          "for the leaked object.")
  @Override
  void managedPoolMustCountLeakedObjects() {
  }
}
