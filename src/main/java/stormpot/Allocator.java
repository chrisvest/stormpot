package stormpot;

public interface Allocator<T extends Poolable> {

  T allocate(Slot slot);

  void deallocate(Poolable poolable);
}