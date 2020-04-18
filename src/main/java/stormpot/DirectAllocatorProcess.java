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

import java.util.Objects;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

class DirectAllocatorProcess<T extends Poolable> extends AllocatorProcess<T> {
  private final BlockingQueue<BSlot<T>> live;
  private final RefillPile<T> disregardPile;
  private final BSlot<T> poisonPill;
  private final int size;
  private final AtomicInteger shutdownState;
  private final AtomicInteger poisonedSlots;

  DirectAllocatorProcess(
      BlockingQueue<BSlot<T>> live,
      RefillPile<T> disregardPile,
      RefillPile<T> newAllocations,
      PoolBuilder<T> builder,
      BSlot<T> poisonPill) {
    this.live = live;
    this.disregardPile = disregardPile;
    this.poisonPill = poisonPill;
    this.size = builder.getSize();
    poisonedSlots = new AtomicInteger(0);
    Allocator<T> allocator = builder.getAllocator();
    for (int i = 0; i < size; i++) {
      BSlot<T> slot = new BSlot<>(live, poisonedSlots);
      try {
        slot.obj = allocator.allocate(slot);
        slot.createdNanos = System.nanoTime();
        slot.dead2live();
      } catch (Exception e) {
        throw new RuntimeException("Unexpected exception.", e);
      }
      live.offer(slot);
    }
    shutdownState = new AtomicInteger(size);
  }

  @Override
  Completion shutdown() {
    poisonPill.dead2live();
    live.offer(poisonPill);
    return timeout -> {
      Objects.requireNonNull(timeout, "Timeout cannot be null.");
      if (Thread.interrupted()) {
        throw new InterruptedException("Interrupted while waiting for pool shut down to complete.");
      }
      TimeUnit unit = timeout.getBaseUnit();
      long deadline = timeout.getDeadline();
      disregardPile.refill();
      BSlot<T> slot;
      while (shutdownState.get() > 0 && (slot = live.poll(deadline, unit)) != null) {
        if (slot != poisonPill) {
          shutdownState.getAndDecrement();
        }
        disregardPile.refill();
        deadline = timeout.getTimeLeft(deadline);
      }
      live.offer(poisonPill);
      return shutdownState.get() == 0;
    };
  }

  @Override
  void offerDeadSlot(BSlot<T> slot) {
    if (slot.poison != null) {
      slot.poison = null;
      poisonedSlots.getAndDecrement();
    }
    slot.dead2live();
    live.offer(slot);
  }

  @Override
  void setTargetSize(int size) {
    throw new UnsupportedOperationException("Target size cannot be changed. " +
        "This pool was created with a fixed set of objects using the Pool.of(...) method. " +
        "Attempted to set target size to " + size + ".");
  }

  @Override
  int getTargetSize() {
    return size;
  }

  @Override
  long getAllocationCount() {
    return size;
  }

  @Override
  long getFailedAllocationCount() {
    return 0;
  }

  @Override
  long countLeakedObjects() {
    return -1;
  }
}
