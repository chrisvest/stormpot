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

public class TimeExpiration implements Expiration<Poolable> {

  private final long maxPermittedAgeMillis;

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

  public boolean hasExpired(SlotInfo<? extends Poolable> info) {
    return info.getAgeMillis() > maxPermittedAgeMillis;
  }
}
