/*
 * Copyright (C) 2011-2014 Chris Vest (mr.chrisvest@gmail.com)
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *         http://www.apache.org/licenses/LICENSE-2.0
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
 * This is the standard time based {@link Expiration}. It will invalidate
 * objects based on about how long ago they were allocated.
 *
 * @author Chris Vest <mr.chrisvest@gmail.com>
 * @since 2.2
 */
public final class TimeSpreadExpiration<T extends Poolable> implements Expiration<T> {

  private final long lowerBoundMillis;
  private final long upperBoundMillis;
  private final TimeUnit unit;

  /**
   * Construct a new Expiration that will invalidate objects that are older
   * than the given lower bound, before they get older than the upper bound,
   * in the given time unit.
   *
   * If the `lowerBound` is less than 1, the `upperBound` is less than the
   * `lowerBound`, or the `unit` is `null`, then an
   * {@link java.lang.IllegalArgumentException} will be thrown.
   *
   * @param lowerBound Poolables younger than this, in the given unit, are not
   *                   considered expired. This value must be at least 1.
   * @param upperBound Poolables older than this, in the given unit, are always
   *                   considered expired. This value must be greater than the
   *                   lowerBound.
   * @param unit The {@link TimeUnit} of the bounds values. Never `null`.
   */
  public TimeSpreadExpiration(
      long lowerBound,
      long upperBound,
      TimeUnit unit) {
    if (lowerBound < 1) {
      throw new IllegalArgumentException(
          "The lower bound cannot be less than 1.");
    }
    if (upperBound <= lowerBound) {
      throw new IllegalArgumentException(
          "The upper bound must be greater than the lower bound.");
    }
    if (unit == null) {
      throw new IllegalArgumentException("The TimeUnit cannot be null.");
    }
    this.lowerBoundMillis = unit.toMillis(lowerBound);
    this.upperBoundMillis = unit.toMillis(upperBound);
    this.unit = unit;
  }

  /**
   * Returns `true`, with uniformly increasing probability, if the
   * {@link stormpot.Poolable} represented by the given {@link SlotInfo} is
   * older than the lower bound, eventually returning `true` with
   * 100% certainty when the age of the Poolable is equal to or greater than
   * the upper bound.
   *
   * The uniformity of the random expiration holds regardless of how often a
   * Poolable is checked. That is to say, checking a Poolable more times within
   * an interval of time, does _not_ increase its chances of being
   * declared expired.
   */
  @Override
  public boolean hasExpired(SlotInfo<? extends T> info) {
    long expirationAge = info.getStamp();
    if (expirationAge == 0) {
      long maxDelta = upperBoundMillis - lowerBoundMillis;
      expirationAge = lowerBoundMillis + Math.abs(info.randomInt() % maxDelta);
      info.setStamp(expirationAge);
    }
    long age = info.getAgeMillis();
    return age >= expirationAge;
  }

  /**
   * Produces a String representation of this TimeSpreadExpiration.
   */
  @Override
  public String toString() {
    long lower = unit.convert(lowerBoundMillis, TimeUnit.MILLISECONDS);
    long upper = unit.convert(upperBoundMillis, TimeUnit.MILLISECONDS);
    return "TimeSpreadExpiration(" + lower + " to " + upper + " " + unit + ")";
  }
}
