package stormpot;

public interface ObjectSource<T extends Poolable> {

  T allocate();

  void free(T poolable);
}
