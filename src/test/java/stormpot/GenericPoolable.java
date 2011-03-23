package stormpot;

public class GenericPoolable implements Poolable {
  private final Slot slot;

  public GenericPoolable(Slot slot) {
    this.slot = slot;
  }

  public void release() {
    slot.release();
  }
}
