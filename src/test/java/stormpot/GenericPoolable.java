package stormpot;

public class GenericPoolable implements Poolable {
  private final Allocator objectSource;

  public GenericPoolable(Allocator objectSource) {
    this.objectSource = objectSource;
  }

  public void release() {
    objectSource.free(this);
  }
}
