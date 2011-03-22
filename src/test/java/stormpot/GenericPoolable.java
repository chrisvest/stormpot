package stormpot;

public class GenericPoolable implements Poolable {
  private final ObjectSource objectSource;

  public GenericPoolable(ObjectSource objectSource) {
    this.objectSource = objectSource;
  }

  public void release() {
    objectSource.free(this);
  }
}
