package stormpot.benchmark;

import stormpot.Poolable;
import stormpot.Slot;

public class MyPoolable implements Poolable {
  private Slot slot;
  private long allocated;
  
  public MyPoolable(Slot slot) {
    this.slot = slot;
    this.allocated = Clock.currentTimeMillis();
  }

  @Override
  public void release() {
    slot.release(this);
  }
  
  public boolean olderThan(long timeMillis) {
    return allocated + timeMillis < Clock.currentTimeMillis();
  }
}
