/*
 * Copyright © Chris Vest (mr.chrisvest@gmail.com)
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

/**
 * A basic implementation of the {@link Poolable} interface, which can be extended directly.
 * <p>
 * It is not strictly necessary to extend this class in order to implement the {@link Poolable}
 * interface, but doing so may make your code simpler.
 */
public class BasePoolable implements Poolable {
  /**
   * The {@link Slot} representing this objects place in the pool.
   * The slot is also the facade for client code to {@link Slot#expire(Poolable) explicitly expire}
   * this object, and to {@link Slot#release(Poolable) release} this object back to the pool.
   */
  protected final Slot slot;

  /**
   * Build a {@link BasePoolable} for the given slot.
   *
   * @param slot The {@link Slot} that represents this objects place in the pool.
   */
  public BasePoolable(Slot slot) {
    Objects.requireNonNull(slot, "The slot argument cannot be null.");
    this.slot = slot;
  }

  @Override
  public void release() {
    slot.release(this);
  }

  /**
   * Explicitly expire this object, such that it will not be claimed again once it is released.
   *
   * @see Slot#expire(Poolable)
   */
  public void expire() {
    slot.expire(this);
  }
}
