package stormpot.benchmark;

import stormpot.Poolable;
import stormpot.Slot;

public class StormpotPoolable implements Poolable {
  private Slot slot;
  
  public StormpotPoolable(Slot slot) {
    this.slot = slot;
  }

  @Override
  public void release() {
    slot.release(this);
  }
}
