/*
 * Copyright © 2011-2024 Chris Vest (mr.chrisvest@gmail.com)
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package stormpot;

import java.time.Duration;
import java.util.Objects;
import java.util.concurrent.TimeUnit;

/**
 * A Timeout represents the maximum amount of time a caller is willing to wait
 * for a blocking operation to complete.
 * <p>
 * Timeouts are independent of their units, so two timeouts of equivalent
 * duration but constructed in different units, will be equal to each other and
 * work exactly the same.
 * <p>
 * Timeouts are also independent of "calendar time" in the sense that they
 * represent and work as a duration of absolute time. In other words, timeouts
 * do not grow or shrink with passing leap seconds or daylight savings time
 * adjustments.
 *
 * @author Chris Vest
 */
public final class Timeout {
  static final Timeout ZERO_TIMEOUT = new Timeout(Duration.ZERO);

  private final long timeout;
  private final TimeUnit unit;
  private final long timeoutBase;
  
  /**
   * Construct a new timeout with the given value and unit. The unit cannot be
   * {@code null}, but the timeout value is unrestricted. The meaning of a
   * negative timeout value is specific to the implementation of the use site,
   * but typically means that no amount of blocking or waiting is tolerated.
   * @param timeout A numerical value for the timeout. Can be zero or negative,
   * though the meaning is implementation specific.
   * @param unit The unit of the timeout value. Never {@code null}.
   */
  public Timeout(long timeout, TimeUnit unit) {
    Objects.requireNonNull(unit, "The TimeUnit cannot be null.");
    this.timeout = timeout;
    this.unit = unit;
    this.timeoutBase = getBaseUnit().convert(timeout, unit);
  }

  /**
   * Construct a new timeout with the given duration.
   * An exception will be thrown if the duration cannot be converted to a nanosecond quantity.
   * The duration also cannot be {@code null}.
   * Apart from these two, there are no restrictions on the duration.
   * @param duration A duration to use for the timeout.
   */
  public Timeout(Duration duration) {
    Objects.requireNonNull(duration, "Duration cannot be null.");
    this.timeout = duration.toNanos();
    this.unit = TimeUnit.NANOSECONDS;
    this.timeoutBase = timeout;
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
   * @return The {@link TimeUnit} of the timeout value. Never {@code null}.
   */
  public TimeUnit getUnit() {
    return unit;
  }

  /**
   * Get the timeout value in terms of the {@link #getBaseUnit() base unit}.
   * @return A numerical value of the timeout. Possibly zero or negative.
   */
  public long getTimeoutInBaseUnit() {
    return timeoutBase;
  }

  /**
   * Get the unit of precision for the underlying clock.
   * @return TimeUnit The unit of precision used by the clock in this Timeout.
   */
  public TimeUnit getBaseUnit() {
    return TimeUnit.NANOSECONDS;
  }

  @Override
  public int hashCode() {
    return 31 * (1 + Long.hashCode(timeoutBase));
  }

  /**
   * Timeouts of equivalent duration are equal, even if they were constructed
   * with different units.
   * @return {@code true} if this Timeout value is equal to the given
   * Timeout value, {@code false} otherwise.
   */
  @Override
  public boolean equals(Object obj) {
      if (obj instanceof Timeout that) {
        return this.timeoutBase == that.timeoutBase;
      }
    return false;
  }
}
