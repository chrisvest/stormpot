package stormpot;

public class BasicPool<T extends Poolable> implements Pool<T> {

  private final Allocator<? extends T> allocator;

  public BasicPool(Config config, Allocator<? extends T> objectSource) {
    this.allocator = objectSource;
  }

  public T claim() {
    return allocator.allocate(null);
  }
}
