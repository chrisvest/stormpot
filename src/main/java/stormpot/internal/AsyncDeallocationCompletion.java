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

import java.util.function.Consumer;

/**
 * Signals the completion of an asynchronous deallocation, and delivers the slot back to the main allocator thread.
 */
public final class AsyncDeallocationCompletion implements Task {
  final BSlot<? extends Poolable> slot;
  private Consumer<BSlot<?>> andThen;

  /**
   * Create a new asynchronous deallocation completion.
   *
   * @param <T> The type of object that was deallocated.
   * @param slot The slot that was deallocated.
   * @param andThen And optional {@link Consumer} of {@linkplain BSlot} to run after this task has completed.
   */
  @SuppressWarnings("unchecked")
  public <T extends Poolable> AsyncDeallocationCompletion(BSlot<T> slot, Consumer<BSlot<T>> andThen) {
    this.slot = slot;
    this.andThen = (Consumer<BSlot<?>>) (Consumer<?>) andThen;
  }

  void doComplete(BAllocThread<? extends Poolable> allocThread) {
    allocThread.maybeReplacePriorGenerationSlot(slot);
    if (andThen != null) {
      andThen.accept(slot);
      andThen = null;
    }
  }
}
