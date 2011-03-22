package stormpot;

public class BasicPool<T extends Poolable> implements Pool<T> {

  private final Allocator<? extends T> objectSource;

  public BasicPool(Config config, Allocator<? extends T> objectSource) {
    this.objectSource = objectSource;
  }

  public T claim() {
    return objectSource.allocate();
  }
}
