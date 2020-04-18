/*
 * Copyright © 2011-2019 Chris Vest (mr.chrisvest@gmail.com)
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

import java.util.concurrent.BlockingQueue;
import java.util.concurrent.atomic.AtomicReference;

/**
 * A `RefillPile` can collect objects, in a concurrent and wait-free manner,
 * before releasing them all to a queue.
 *
 * @param <T>
 */
@SuppressWarnings("unchecked")
final class RefillPile<T extends Poolable>
    extends AtomicReference<RefillSlot<T>> {
  private static final long serialVersionUID = 2374582348576873465L;
  private static final RefillSlot<Poolable> STACK_END =
      new RefillSlot<>(null);

  private final BlockingQueue<BSlot<T>> refillQueue;

  RefillPile(BlockingQueue<BSlot<T>> refillQueue) {
    this.refillQueue = refillQueue;
    set((RefillSlot<T>) STACK_END);
  }

  /**
   * Push the given slot onto the stack. This method is wait-free.
   * @param slot The slot instance to be pushed onto the stack.
   */
  void push(BSlot<T> slot) {
    RefillSlot<T> element = new RefillSlot<>(slot);
    element.next = getAndSet(element);
  }

  BSlot<T> pop() {
    RefillSlot<T> element;
    RefillSlot<T> next;
    do {
      element = get();
      if (element == STACK_END) {
        return null;
      }
      next = element.next;
    } while ((next == null && pause()) || !compareAndSet(element, next));
    return element.slot;
  }

  private boolean pause() {
    Thread.onSpinWait();
    return true;
  }

  /**
   * Refill the target queue with all the slots that have been pushed onto this stack.
   * This method atomically pops all elements from the stack at once, and then pushed onto the
   * queue one by one.
   * @return `true` if any slots has been offered to the queue, or `false` if there were no
   * slots in the pile.
   */
  boolean refill() {
    RefillSlot<T> stack = getAndSet((RefillSlot<T>) STACK_END);
    int count = 0;
    while (stack != STACK_END) {
      count++;
      refillQueue.offer(stack.slot);
      RefillSlot<T> next;
      do {
        next = stack.next;
      } while (next == null);
      stack = next;
    }
    return count > 0;
  }
}
