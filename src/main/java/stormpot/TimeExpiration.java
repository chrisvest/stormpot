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
 * This is a time based {@link Expiration}. It will invalidate
 * objects based on how long ago they were allocated.
 * 
 * @author Chris Vest <mr.chrisvest@gmail.com>
 */
public final class TimeExpiration<T extends Poolable> implements Expiration<T> {

  private final long maxPermittedAgeMillis;
  private final TimeUnit unit;

  /**
   * Construct a new Expiration that will invalidate objects that are older
   * than the provided span of time in the given unit.
   *
   * If the `maxPermittedAge` is less than one, or the `unit` is `null`, then
   * an {@link IllegalArgumentException} will be thrown.
   * 
   * @param maxPermittedAge Poolables older than this, in the given unit, will
   * be considered expired. This value must be at least 1.
   * @param unit The {@link TimeUnit} of the maximum permitted age. Never
   * `null`.
   */
  public TimeExpiration(long maxPermittedAge, TimeUnit unit) {
    if (maxPermittedAge < 1) {
      throw new IllegalArgumentException(
          "Max permitted age cannot be less than 1");
    }
    if (unit == null) {
      throw new IllegalArgumentException("The TimeUnit cannot be null");
    }
    maxPermittedAgeMillis = unit.toMillis(maxPermittedAge);
    this.unit = unit;
  }

  /**
   * Returns `true` if the {@link Poolable} represented by the given
   * {@link SlotInfo} is older than the maximum age permitted by this
   * TimeExpiration.
   */
  @Override
  public boolean hasExpired(SlotInfo<? extends T> info) {
    return info.getAgeMillis() > maxPermittedAgeMillis;
  }

  @Override
  public String toString() {
    long time = unit.convert(maxPermittedAgeMillis, TimeUnit.MILLISECONDS);
    return "TimeExpiration(" + time + " " + unit + ")";
  }
}
