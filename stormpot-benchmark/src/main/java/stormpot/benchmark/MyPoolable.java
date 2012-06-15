package stormpot.benchmark;

import stormpot.Poolable;
import stormpot.Slot;

public class MyPoolable implements Poolable {
  private Slot slot;
  
  public MyPoolable(Slot slot) {
    this.slot = slot;
  }

  @Override
  public void release() {
    slot.release(this);
  }
}
