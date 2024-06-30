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

import java.io.Serial;

/**
 * The PoolException may be thrown by a pool implementation in a number of
 * circumstances:
 * <ul>
 * <li>If claim is called and the pool needs to {@link Allocator#allocate(Slot) allocate}
 * a new object, but the allocation fails by returning {@code null} or throwing an exception.</li>
 * <li>Likewise if the {@link Reallocator#reallocate(Slot, Poolable)} method
 *   return {@code null} or throw an exception.</li>
 * <li>If the {@link Slot#release(Poolable)} method is misused, and the pool is
 *   able to detect this.</li>
 * <li>If the {@link Expiration#hasExpired(SlotInfo) expiration check} throws an
 *   exception.</li>
 * </ul>
 *
 * @author Chris Vest
 */
public class PoolException extends RuntimeException {
  @Serial
  private static final long serialVersionUID = -1908093409167496640L;

  /**
   * Construct a new PoolException with the given message.
   * @param message A description of the exception to be returned from
   * {@link #getMessage()}.
   * @see RuntimeException#RuntimeException(String)
   */
  public PoolException(String message) {
    super(message);
  }

  /**
   * Construct a new PoolException with the given message and cause.
   *
   * @param message A description for the exception to be returned form
   * {@link #getMessage()}.
   * @param cause The underlying cause of this exception, as to be shown in the
   * stack trace, and available through {@link #getCause()}.
   * @see RuntimeException#RuntimeException(String, Throwable)
   */
  public PoolException(String message, Throwable cause) {
    super(message, cause);
  }
}
