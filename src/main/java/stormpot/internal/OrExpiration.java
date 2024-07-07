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

/**
 * Provides a way to compose {@link Expiration}s.
 * <p>
 * Given two {@link Expiration}s, this class considers that a slot is expired if any of the
 * {@link Expiration} returns {@code true}. This makes it easy to have an {@link Expiration} that
 * expires both on time ({@link Expiration#after(long, TimeUnit)}) and some other criteria.
 *
 * @author Guillaume Lederrey
 * @since 2.4
 */
public final class OrExpiration<T extends Poolable> implements Expiration<T> {
  private final Expiration<T> firstExpiration;
  private final Expiration<T> secondExpiration;

  public OrExpiration(Expiration<T> firstExpiration, Expiration<T> secondExpiration) {
    this.firstExpiration = firstExpiration;
    this.secondExpiration = secondExpiration;
  }

  /**
   * Returns {@code true} if any of the given {@link Expiration} has expired.
   */
  @Override
  public boolean hasExpired(SlotInfo<? extends T> info) throws Exception {
    return firstExpiration.hasExpired(info)
            || secondExpiration.hasExpired(info);
  }
}
