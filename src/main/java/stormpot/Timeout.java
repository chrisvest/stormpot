package stormpot;

import java.util.concurrent.TimeUnit;

public class Timeout {
  private final long timeout;
  private final TimeUnit unit;

  public Timeout(long timeout, TimeUnit unit) {
    if (unit == null) {
      throw new IllegalArgumentException("unit cannot be null");
    }
    this.timeout = timeout;
    this.unit = unit;
  }

  public long getTimeout() {
    return timeout;
  }

  public TimeUnit getUnit() {
    return unit;
  }

  public long getDeadlineNanos() {
//    return now() + unit.toMillis(timeout);
    return now() + unit.toNanos(timeout);
  }

  public long getTimeLeftNanos(long deadline) {
    return deadline - now();
  }

  private long now() {
//    return System.currentTimeMillis();
    return System.nanoTime();
  }

  public TimeUnit getBaseUnit() {
    return TimeUnit.NANOSECONDS;
  }
}
