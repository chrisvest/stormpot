package stormpot.qpool;

import java.util.concurrent.TimeUnit;

import stormpot.Completion;

final class QPoolShutdownCompletion implements Completion {
  private final QAllocThread allocThread;
  
  public QPoolShutdownCompletion(QAllocThread allocThread) {
    this.allocThread = allocThread;
  }
  
  public void await() throws InterruptedException {
    allocThread.await();
  }

  public boolean await(long timeout, TimeUnit unit)
      throws InterruptedException {
    if (unit == null) {
      throw new IllegalArgumentException("timeout TimeUnit cannot be null");
    }
    return allocThread.await(timeout, unit);
  }
}