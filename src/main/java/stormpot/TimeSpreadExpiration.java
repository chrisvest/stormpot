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

import java.util.Objects;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.TimeUnit;

/**
 * This is the standard time based {@link Expiration}. It will invalidate
 * objects based on about how long ago they were allocated.
 *
 * @author Chris Vest
 * @since 2.2
 */
final class TimeSpreadExpiration<T extends Poolable> implements Expiration<T> {

  private final long fromMillis;
  private final long toMillis;
  private final TimeUnit unit;

  /**
   * Construct a new Expiration that will invalidate objects that are older
   * than the given lower bound, before they get older than the upper bound,
   * in the given time unit.
   * <p>
   * If the {@code lowerBound} is less than 1, the {@code upperBound} is less than the
   * {@code lowerBound}, or the {@code unit} is {@code null}, then an
   * {@link java.lang.IllegalArgumentException} will be thrown.
   *
   * @param lowerBound Poolables younger than this, in the given unit, are not
   *                   considered expired. This value must be at least 1.
   * @param upperBound Poolables older than this, in the given unit, are always
   *                   considered expired. This value must be greater than the
   *                   lowerBound.
   * @param unit The {@link TimeUnit} of the bounds values. Never {@code null}.
   */
  TimeSpreadExpiration(
      long lowerBound,
      long upperBound,
      TimeUnit unit) {
    Objects.requireNonNull(unit, "TimeUnit cannot be null.");
    if (lowerBound < 1) {
      throw new IllegalArgumentException(
          "The lower bound cannot be less than 1.");
    }
    if (upperBound < lowerBound) {
      throw new IllegalArgumentException(
          "The upper bound must be greater than or equal to the lower bound.");
    }
    this.fromMillis = unit.toMillis(lowerBound);
    this.toMillis = unit.toMillis(upperBound);
    this.unit = unit;
  }

  /**
   * Returns {@code true}, with uniformly increasing probability, if the
   * {@link stormpot.Poolable} represented by the given {@link SlotInfo} is
   * older than the lower bound, eventually returning {@code true} with
   * 100% certainty when the age of the Poolable is equal to or greater than
   * the upper bound.
   * <p>
   * The uniformity of the random expiration holds regardless of how often a
   * Poolable is checked. That is to say, checking a Poolable more times within
   * an interval of time, does _not_ increase its chances of being
   * declared expired.
   */
  @Override
  public boolean hasExpired(SlotInfo<? extends T> info) {
    long expirationAge = info.getStamp();
    if (expirationAge == 0) {
      expirationAge = computeExpirationDeadline();
      info.setStamp(expirationAge);
    }
    long age = info.getAgeMillis();
    return age >= expirationAge;
  }

  long computeExpirationDeadline() {
    long maxDelta = toMillis - fromMillis;
    ThreadLocalRandom prng = ThreadLocalRandom.current();
    long fudgeFactor = fromMillis == toMillis ? 0 : Math.abs(prng.nextInt() % maxDelta);
    return fromMillis + fudgeFactor;
  }

  /**
   * Produces a String representation of this TimeSpreadExpiration.
   */
  @Override
  public String toString() {
    long lower = unit.convert(fromMillis, TimeUnit.MILLISECONDS);
    long upper = unit.convert(toMillis, TimeUnit.MILLISECONDS);
    return "TimeSpreadExpiration(" + lower + " to " + upper + " " + unit + ")";
  }
}
