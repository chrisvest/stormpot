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

import stormpot.PoolException;
import stormpot.PoolTap;
import stormpot.Poolable;
import stormpot.Timeout;

/**
 * The claim method in this pool tap offers similar thread-safety and
 * performance to the default thread-safe pool tap.
 *
 * @param <T> The poolable type.
 */
public final class BlazePoolThreadSafeTap<T extends Poolable> implements PoolTap<T> {
  private final BlazePool<T> pool;

  public BlazePoolThreadSafeTap(BlazePool<T> pool) {
    this.pool = pool;
  }

  @Override
  public T claim(Timeout timeout) throws PoolException, InterruptedException {
    return pool.claim(timeout);
  }

  @Override
  public T tryClaim() throws PoolException {
    return pool.tryClaim();
  }
}
