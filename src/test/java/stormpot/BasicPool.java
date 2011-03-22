package stormpot;

public class BasicPool<T extends Poolable> implements Pool<T> {

  private final ObjectSource<? extends T> objectSource;

  public BasicPool(Config config, ObjectSource<? extends T> objectSource) {
    this.objectSource = objectSource;
  }

  public T claim() {
    return objectSource.allocate();
  }
}
