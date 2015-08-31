/*
 * Copyright (C) 2011-2014 Chris Vest (mr.chrisvest@gmail.com)
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *         http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package stormpot;

import java.util.concurrent.BlockingQueue;
import java.util.concurrent.atomic.AtomicReference;

@SuppressWarnings("unchecked")
final class DisregardBPile<T extends Poolable>
    extends AtomicReference<DisregardedBSlot<T>> {
  private static final DisregardedBSlot<Poolable> STACK_END =
      new DisregardedBSlot<>(null);

  private final BlockingQueue<BSlot<T>> refillQueue;

  public DisregardBPile(BlockingQueue<BSlot<T>> refillQueue) {
    this.refillQueue = refillQueue;
    set((DisregardedBSlot<T>) STACK_END);
  }

  public void addSlot(BSlot<T> slot) {
    DisregardedBSlot<T> element = new DisregardedBSlot<T>(slot);
    element.next = getAndSet(element);
  }

  public boolean refillQueue() {
    DisregardedBSlot<T> stack = getAndSet((DisregardedBSlot<T>) STACK_END);
    int count = 0;
    while (stack != STACK_END) {
      count++;
      refillQueue.offer(stack.slot);
      DisregardedBSlot<T> next;
      do {
        next = stack.next;
      } while (next == null);
      stack = next;
    }
    return count > 0;
  }
}
