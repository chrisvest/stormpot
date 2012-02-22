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
    return now() + unit.toNanos(timeout);
  }

  public long getTimeLeftNanos(long deadline) {
    return deadline - now();
  }

  private long now() {
    return System.nanoTime();
  }

  public TimeUnit getBaseUnit() {
    return TimeUnit.NANOSECONDS;
  }

  @Override
  public int hashCode() {
    long nanos = unit.toNanos(timeout);
    return 31 * 1 + (int) (nanos ^ (nanos >>> 32));
  }

  @Override
  public boolean equals(Object obj) {
    if (!(obj instanceof Timeout)) {
      return false;
    }
    Timeout that = (Timeout) obj;
    long thisNanos = unit.toNanos(timeout);
    long thatNanos = that.unit.toNanos(that.timeout);
    return thisNanos == thatNanos;
  }
}
