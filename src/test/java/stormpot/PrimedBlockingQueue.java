package stormpot;

import java.util.Queue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;

final class PrimedBlockingQueue<T> extends LinkedBlockingQueue<T> {
  private static final long serialVersionUID = -2138789305960877995L;
  
  private final Queue<Callable<T>> calls;
  T lastValue;

  PrimedBlockingQueue(Queue<Callable<T>> calls) {
    this.calls = calls;
  }

  public T poll(long timeout, TimeUnit unit)
      throws InterruptedException {
    Callable<T> callable = calls.poll();
    if (callable != null) {
      lastValue = callable.call();
    }
    return lastValue;
  }

  public boolean offer(T e) {
    return false;
  }
}
