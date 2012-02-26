/*
 * Copyright 2012 Chris Vest
 * 
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 * 
 *      http://www.apache.org/licenses/LICENSE-2.0
 * 
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package stormpot;

import java.util.concurrent.TimeUnit;

/**
 * A Timeout represents the maximum amount of time a caller is willing to wait
 * for a blocking operation to complete.
 * @author Chris Vest &lt;mr.chrisvest@gmail.com&gt;
 */
public class Timeout {
  private final long timeout;
  private final TimeUnit unit;
  
  /**
   * Construct a new timeout with the given value and unit. The unit cannot be
   * null, but the timeout value is unrestricted. The meaning of a negative
   * timeout value is specific to the implementation of the use site, but
   * typically means that no amount of blocking or waiting is tolerated.
   * @param timeout A numerical value for the timeout. Can be zero or negative,
   * though the meaning is implementation specific.
   * @param unit The unit of the timeout value. Never null.
   */
  public Timeout(long timeout, TimeUnit unit) {
    if (unit == null) {
      throw new IllegalArgumentException("unit cannot be null");
    }
    this.timeout = timeout;
    this.unit = unit;
  }

  /**
   * Get the timeout value in terms of the {@link #getUnit() unit}.
   * @return A numerical value of the timeout. Possibly zero or negative.
   */
  public long getTimeout() {
    return timeout;
  }

  /**
   * Get the unit for the {@link #getTimeout() timeout value}.
   * @return The {@link TimeUnit} of the timeout value. Never null.
   */
  public TimeUnit getUnit() {
    return unit;
  }

  /**
   * Calculate a deadline, as an instant in the future, in terms of the
   * {@link #getBaseUnit() base unit}. Once you have a deadline, you can ask
   * how much time is left until it transpires, with the
   * {@link #getTimeLeft(long)}, giving the deadline as an argument.
   * <p>
   * If the {@link #getTimeout() timeout value} is really small, zero or
   * negative, then the deadline might be an instant in the past.
   * @return A numerical value that represents the deadline from "now" until
   * this timeout has passed.
   */
  public long getDeadline() {
    return now() + unit.toNanos(timeout);
  }

  /**
   * Calculate the amount of time that is left within the deadline, in terms
   * of the {@link #getBaseUnit() base unit}. The argument is a deadline value
   * that has been calculated with the {@link #getDeadline()} method.
   * @param deadline The reference deadline from {@link #getDeadline()}.
   * @return The amount of time, in terms of the
   * {@link #getBaseUnit() base unit}, that is left within the deadline. If
   * this value is negative, then you have overrun the deadline.
   */
  public long getTimeLeft(long deadline) {
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
