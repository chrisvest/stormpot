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
package stormpot.internal;

import stormpot.Expiration;
import stormpot.Poolable;
import stormpot.SlotInfo;

import java.util.concurrent.TimeUnit;

public final class EveryExpiration<T extends Poolable> implements Expiration<T> {
  private final Expiration<T> innerExpiration;
  private final TimeSpreadExpiration<T> timeExpiration;

  public EveryExpiration(Expiration<T> innerExpiration, long fromTime, long toTime, TimeUnit unit) {
    this.innerExpiration = innerExpiration;
    timeExpiration = new TimeSpreadExpiration<>(fromTime, toTime, unit);
  }

  @Override
  public boolean hasExpired(SlotInfo<? extends T> info) throws Exception {
    if (timeExpiration.hasExpired(info)) {
      // Time-bsaed expiration triggered. Push deadline out.
      info.setStamp(info.getStamp() + timeExpiration.computeExpirationDeadline());
      return innerExpiration.hasExpired(info);
    }
    return false;
  }
}
