package stormpot;

public interface Allocator<T extends Poolable> {

  T allocate();

  void free(T poolable);
}
