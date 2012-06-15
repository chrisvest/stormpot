package stormpot.benchmark;

import stormpot.Allocator;
import stormpot.Poolable;
import stormpot.Slot;

public class StormpotPoolableAllocator implements Allocator<Poolable> {
  @Override
  public Poolable allocate(Slot slot) throws Exception {
    return new MyPoolable(slot);
  }

  @Override
  public void deallocate(Poolable poolable) throws Exception {
  }
}
