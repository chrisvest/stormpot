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
package stormpot.internal;

import stormpot.Poolable;

/**
 * Signals the completion of an asynchronous allocation, and delivers the result back to the main allocator thread.
 */
public final class AsyncAllocationCompletion implements Task {
  final BSlot<? extends Poolable> slot;
  final boolean success;

  /**
   * Create a new asynchronous allocation completion.
   * @param slot The slot that was allocated into.
   * @param success {@code true} if the allocation was successful, otherwise {@code false}.
   * @param <T> The type of object that was allocated.
   */
  public <T extends Poolable> AsyncAllocationCompletion(
          BSlot<T> slot, boolean success) {
    this.slot = slot;
    this.success = success;
  }
}
