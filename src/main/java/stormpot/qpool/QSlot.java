package stormpot.qpool;

import java.util.concurrent.BlockingQueue;
import java.util.concurrent.atomic.AtomicBoolean;

import stormpot.Poolable;
import stormpot.Slot;

class QSlot<T extends Poolable> implements Slot {
  final BlockingQueue<QSlot<T>> live;
  final AtomicBoolean claimed;
  T obj;
  Exception poison;
  long expires;
  
  public QSlot(BlockingQueue<QSlot<T>> live) {
    this.live = live;
    this.claimed = new AtomicBoolean();
  }
  
  public void claim() {
    claimed.set(true);
  }

  public boolean expired() {
    return expires < System.currentTimeMillis();
  }
  
  public void release(Poolable obj) {
    if (claimed.compareAndSet(true, false)) {
      live.offer(this);
    }
  }
}