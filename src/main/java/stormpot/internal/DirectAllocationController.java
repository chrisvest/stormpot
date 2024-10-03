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

import stormpot.Allocator;
import stormpot.Completion;
import stormpot.PoolBuilder;
import stormpot.Poolable;

import java.util.Objects;
import java.util.concurrent.LinkedTransferQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

public final class DirectAllocationController<T extends Poolable> extends AllocationController<T> {
  private final LinkedTransferQueue<BSlot<T>> live;
  private final RefillPile<T> disregardPile;
  private final BSlot<T> poisonPill;
  private final int size;
  private final AtomicInteger shutdownState;
  private final AtomicInteger poisonedSlots;

  DirectAllocationController(
      LinkedTransferQueue<BSlot<T>> live,
      RefillPile<T> disregardPile,
      PoolBuilder<T> builder,
      BSlot<T> poisonPill) {
    this.live = live;
    this.disregardPile = disregardPile;
    this.poisonPill = poisonPill;
    this.size = builder.getSize();
    poisonedSlots = new AtomicInteger();
    Allocator<T> allocator = builder.getAllocator();
    boolean optimizeForMemory = builder.isOptimizeForReducedMemoryUsage();
    for (int i = 0; i < size; i++) {
      BSlot<T> slot = optimizeForMemory ?
              new BSlot<>(live, poisonedSlots) : new BSlotPadded<>(live, poisonedSlots);
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
      long startNanos = NanoClock.nanoTime();
      long timeoutNanos = timeout.getTimeoutInBaseUnit();
      long timeoutLeft = timeoutNanos;
      disregardPile.refill();
      BSlot<T> slot;
      while (shutdownState.get() > 0 && (slot = live.poll(timeoutLeft, unit)) != null) {
        if (slot != poisonPill) {
          shutdownState.getAndDecrement();
        }
        disregardPile.refill();
        timeoutLeft = NanoClock.timeoutLeft(startNanos, timeoutNanos);
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

  @Override
  public int allocatedSize() {
    return size;
  }

  @Override
  int inUse() {
    int inUse = 0;
    int liveSize = 0;
    for (BSlot<T> slot: live) {
      liveSize++;
      if (slot.isClaimedOrThreadLocal()) {
        inUse++;
      }
    }
    return size - liveSize + inUse;
  }  
}
