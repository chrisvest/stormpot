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

import stormpot.Poolable;

/**
 * The node type of {@link RefillPile}.
 * @param <T> The concrete poolable type.
 */
public final class RefillSlot<T extends Poolable> {
  final BSlot<T> slot;
  volatile RefillSlot<T> next;

  RefillSlot(BSlot<T> slot) {
    this.slot = slot;
  }

  @Override
  public String toString() {
    return "RefillSlot@" + Integer.toHexString(System.identityHashCode(this)) + "[slot=" + slot + ", next=" +
            (next == null ? "<null>" : "RefillSlot@" + Integer.toHexString(System.identityHashCode(this))) + "]";
  }
}
