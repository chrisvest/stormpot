package stormpot.qpool;

import java.util.Queue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;

import stormpot.Poolable;
import stormpot.qpool.QAllocThread_NullPollFromLiveWhileShrinking_Test.Callable;

final class PrimedBlockingQueue extends LinkedBlockingQueue<QSlot<Poolable>> {
  private static final long serialVersionUID = -2138789305960877995L;
  private final Queue<Callable<QSlot<Poolable>>> calls;
  QSlot<Poolable> lastValue;

  PrimedBlockingQueue(Queue<Callable<QSlot<Poolable>>> calls) {
    this.calls = calls;
  }

  public QSlot<Poolable> poll(long timeout, TimeUnit unit)
      throws InterruptedException {
    Callable<QSlot<Poolable>> callable = calls.poll();
    if (callable != null) {
      lastValue = callable.call();
    }
    return lastValue;
  }

  public boolean offer(QSlot<Poolable> e) {
    return false;
  }
}
