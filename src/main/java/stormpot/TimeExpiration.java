/*
 * Copyright 2012-2014 Chris Vest
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
 * This is a time based {@link Expiration}. It will invalidate
 * objects based on how long ago they were allocated.
 * 
 * @author Chris Vest &lt;mr.chrisvest@gmail.com&gt;
 */
public class TimeExpiration implements Expiration<Poolable> {

  private final long maxPermittedAgeMillis;

  /**
   * Construct a new Expiration that will invalidate objects that are older
   * than the provided span of time in the given unit.
   * <p>
   * If the <code>maxPermittedAge</code> is less than one, or the
   * <code>unit</code> is <code>null</code>, then an
   * {@link IllegalArgumentException} will be thrown.
   * 
   * @param maxPermittedAge Poolables older than this, in the given unit, will
   * be considered expired. This value must be at least 1.
   * @param unit The {@link TimeUnit} of the maximum permitted age. Never
   * <code>null</code>.
   */
  public TimeExpiration(long maxPermittedAge, TimeUnit unit) {
    if (maxPermittedAge < 1) {
      throw new IllegalArgumentException(
          "max permitted age cannot be less than 1");
    }
    if (unit == null) {
      throw new IllegalArgumentException("unit cannot be null");
    }
    maxPermittedAgeMillis = unit.toMillis(maxPermittedAge);
  }

  /**
   * Returns <code>true</code> if the {@link Poolable} represented by the given
   * {@link SlotInfo} is older than the maximum age permitted by this
   * TimeExpiration.
   */
  @Override
  public boolean hasExpired(SlotInfo<? extends Poolable> info) {
    return info.getAgeMillis() > maxPermittedAgeMillis;
  }
}
