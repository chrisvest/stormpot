package stormpot;

public class GenericAllocator implements Allocator {

  public Poolable allocate(Slot slot) {
    return new GenericPoolable(slot);
  }
}
