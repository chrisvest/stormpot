/*
 * Copyright © 2011 Chris Vest (mr.chrisvest@gmail.com)
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
package stormpot.internal;

import stormpot.Expiration;
import stormpot.Poolable;
import stormpot.SlotInfo;

import java.util.Objects;
import java.util.concurrent.TimeUnit;

/**
 * This is a time based {@link Expiration}. It will invalidate
 * objects based on how long ago they were allocated.
 *
 * @param <T> The concrete poolable type.
 * @author Chris Vest
 */
public final class TimeExpiration<T extends Poolable> implements Expiration<T> {

  private final long maxPermittedAgeMillis;
  private final TimeUnit unit;

  /**
   * Construct a new Expiration that will invalidate objects that are older
   * than the provided span of time in the given unit.
   * <p>
   * If the {@code maxPermittedAge} is less than one, or the {@code unit} is {@code null}, then
   * an {@link IllegalArgumentException} will be thrown.
   * 
   * @param maxPermittedAge Poolables older than this, in the given unit, will
   * be considered expired. This value must be at least 1.
   * @param unit The {@link TimeUnit} of the maximum permitted age. Never
   * {@code null}.
   */
  public TimeExpiration(long maxPermittedAge, TimeUnit unit) {
    Objects.requireNonNull(unit, "TimeUnit cannot be null.");
    if (maxPermittedAge < 1) {
      throw new IllegalArgumentException(
          "Max permitted age cannot be less than 1");
    }
    maxPermittedAgeMillis = unit.toMillis(maxPermittedAge);
    this.unit = unit;
  }

  /**
   * Returns {@code true} if the {@link Poolable} represented by the given
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
