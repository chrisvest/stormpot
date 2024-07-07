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

/**
 * An informative interface, used by {@link Expiration} instances to
 * determine if a slot has expired or is still invalid for claiming.
 *
 * @author Chris Vest
 * @param <T> The type of Poolables that this Expiration is able to examine.
 */
public interface SlotInfo<T extends Poolable> {

  /**
   * Get the approximate number of milliseconds that have transpired since the
   * object was allocated.
   * @return The age of the object in milliseconds.
   */
  long getAgeMillis();

  /**
   * Get the approximate {@link System#nanoTime()} timestamp for when the object
   * was allocated.
   * @return The object allocation {@link System#nanoTime()} timestamp.
   */
  long getCreatedNanoTime();

  /**
   * Get the number of times the object has been claimed since it was
   * allocated.
   * @return The objects claim count.
   */
  long getClaimCount();

  /**
   * Get the Poolable object represented by this SlotInfo instance.
   * <p>
   * WARNING: Do not {@link Poolable#release() release()}
   * Poolables from within an {@link Expiration} &mdash; doing so is a user
   * error, and the behaviour of the pool in such a situation is unspecified
   * and implementation specific. This means that deadlocks and infinite
   * loops are possible outcomes as well.
   * <p>
   * <strong>WARNING:</strong> Also note that accessing the Poolable through
   * this method, from your {@link Expiration} implementation, is a
   * potentially concurrent access. This means that you need to take
   * thread-safety issues into consideration - especially if you intend on
   * manipulating the Poolable. In particular, you might be racing with other
   * threads that are checking if this Poolable is expired or not, and they
   * might even have claimed the Poolable and put it to use, by the time it
   * is returned from this method.
   *
   * @return The Poolable being examined for validity. Never {@code null}.
   */
  T getPoolable();

  /**
   * Get the stamp value that has been set on this SlotInfo, or 0 if none has
   * been set since the Poolable was allocated.
   * <p>
   * Apart from the zero-value, the actual meaning of this value is completely
   * up to the {@link Expiration} that sets it.
   * @return The current stamp value.
   */
  long getStamp();

  /**
   * Set the stamp value on this SlotInfo.
   * <p>
   * This method is only thread-safe to call from within the scope of the
   * {@link Expiration#hasExpired(SlotInfo)} method.
   * <p>
   * The stamp value is 0 by default, if it has not been set after the Poolable
   * has been allocated. Its meaning is otherwise up to the particular
   * Expiration that might use it.
   * @param stamp The new stamp value.
   */
  void setStamp(long stamp);
}
