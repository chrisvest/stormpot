package stormpot;

import java.util.concurrent.Semaphore;

public class SemaphoreReleasingSlot implements Slot {
  private final Semaphore semaphore;

  public SemaphoreReleasingSlot(Semaphore semaphore) {
    this.semaphore = semaphore;
  }

  public void release() {
    semaphore.release();
  }
}
