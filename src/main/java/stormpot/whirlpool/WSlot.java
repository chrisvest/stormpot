package stormpot.whirlpool;

import stormpot.Poolable;
import stormpot.Slot;

class WSlot implements Slot {
  long created;
  Object obj;
  WSlot next;
  final Whirlpool pool;
  Exception poison;
  boolean claimed;
  
  public WSlot(Whirlpool pool) {
    this.pool = pool;
  }

  public void release(Poolable obj) {
    if (obj != this.obj || !claimed) {
      throw new IllegalStateException("illegal release");
    }
    claimed = false;
    pool.release(this);
  }

  @Override
  public String toString() {
    return "WSlot [" + obj + "] @ " + Integer.toHexString(hashCode());
  }
}