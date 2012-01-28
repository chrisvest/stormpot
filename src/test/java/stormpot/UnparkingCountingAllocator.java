package stormpot;

import java.util.concurrent.locks.LockSupport;

final class UnparkingCountingAllocator extends
    CountingAllocator {
  private final Thread main;

  UnparkingCountingAllocator(Thread main) {
    this.main = main;
  }

  @Override
  public GenericPoolable allocate(Slot slot) throws Exception {
    try {
      return super.allocate(slot);
    } finally {
      LockSupport.unpark(main);
    }
  }

  @Override
  public void deallocate(GenericPoolable poolable) throws Exception {
    try {
      super.deallocate(poolable);
    } finally {
      LockSupport.unpark(main);
    }
  }
}