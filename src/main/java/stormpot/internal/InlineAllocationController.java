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

import stormpot.Completion;
import stormpot.MetricsRecorder;
import stormpot.Poolable;
import stormpot.Reallocator;
import stormpot.Timeout;

import java.lang.invoke.MethodHandles;
import java.lang.invoke.VarHandle;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.LinkedTransferQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

/**
 * The {@link AllocationController} implementation for the {@link AllocationProcessMode#INLINE} mode.
 * @param <T> The concrete poolable type.
 */
public final class InlineAllocationController<T extends Poolable> extends AllocationController<T> {
  private static final VarHandle SIZE;
  private static final VarHandle ALLOC_COUNT;
  private static final VarHandle FAILED_ALLOC_COUNT;

  static {
    try {
      MethodHandles.Lookup lookup = MethodHandles.lookup();
      Class<?> receiver = InlineAllocationController.class;
      SIZE = lookup.findVarHandle(receiver, "size", long.class)
              .withInvokeExactBehavior();
      ALLOC_COUNT = lookup.findVarHandle(receiver, "allocationCount", long.class)
              .withInvokeExactBehavior();
      FAILED_ALLOC_COUNT = lookup.findVarHandle(receiver, "failedAllocationCount", long.class)
              .withInvokeExactBehavior();
    } catch (Exception e) {
      throw new LinkageError("Failed to create VarHandle.", e);
    }
  }

  private final LinkedTransferQueue<BSlot<T>> live;
  private final RefillPile<T> disregardPile;
  private final RefillPile<T> newAllocations;
  private final BSlot<T> poisonPill;
  private final MetricsRecorder metricsRecorder;
  private final AtomicLong poisonedSlots;
  private final PreciseLeakDetector leakDetector;
  private final Reallocator<T> allocator;
  private final boolean optimizeForMemory;

  private volatile long targetSize;
  @SuppressWarnings("unused") // Assigned via VarHandle.
  private volatile long size;
  private volatile boolean shutdown;
  @SuppressWarnings("unused") // Assigned via VarHandle.
  private volatile long allocationCount;
  @SuppressWarnings("unused") // Assigned via VarHandle.
  private volatile long failedAllocationCount;

  InlineAllocationController(
      LinkedTransferQueue<BSlot<T>> live,
      RefillPile<T> disregardPile,
      RefillPile<T> newAllocations,
      PoolBuilderImpl<T> builder,
      BSlot<T> poisonPill) {
    this.live = live;
    this.disregardPile = disregardPile;
    this.newAllocations = newAllocations;
    this.poisonPill = poisonPill;
    this.metricsRecorder = builder.getMetricsRecorder();
    poisonedSlots = new AtomicLong();
    allocator = builder.getAdaptedReallocator();
    optimizeForMemory = builder.isOptimizeForReducedMemoryUsage();
    leakDetector = builder.isPreciseLeakDetectionEnabled() ?
        new PreciseLeakDetector() : null;
    setTargetSize(builder.getSize());
  }

  @Override
  void offerDeadSlot(BSlot<T> slot) {
    if (shutdown) {
      dealloc(slot);
    } else {
      long s = size;
      while (s > targetSize) {
        if (SIZE.compareAndSet(this, s, s - 1L)) {
          deallocSlot(slot);
          return;
        }
      }
      realloc(slot);
    }
  }

  @Override
  synchronized Completion shutdown() {
    if (!shutdown) {
      // All dead slots returned to us will be deallocated from now on.
      shutdown = true;

      // First remove all live objects from circulation.
      disregardPile.refill();
      newAllocations.refill();
      List<BSlot<T>> deferredSlots = new ArrayList<>();
      BSlot<T> slot;
      while ((slot = live.poll()) != null) {
        if (slot.isDead() || slot.live2dead()) {
          dealloc(slot);
          unregisterWithLeakDetector(slot);
          disregardPile.refill();
        } else {
          deferredSlots.add(slot);
        }
      }
      for (BSlot<T> deferredSlot : deferredSlots) {
        live.offer(deferredSlot);
      }

      // Then enter the poison-pill into circulation to unblock claim calls.
      poisonPill.dead2live();
      live.offer(poisonPill);
    }

    // Leave the rest of the deallocations to the blocking completion object.
    return new StackCompletion(this::shutdownCompletion);
  }

  private boolean shutdownCompletion(Timeout timeout) throws InterruptedException {
    if (Thread.interrupted()) {
      throw new InterruptedException();
    }
    if (size == 0) {
      return true;
    }
    BSlot<T> slot;
    long startNanos = NanoClock.nanoTime();
    TimeUnit baseUnit = timeout == null ? null : timeout.getBaseUnit();
    long timeoutNanos = timeout == null ? 0 : timeout.getTimeoutInBaseUnit();
    long timeoutLeft = timeoutNanos;
    long maxWaitQuantum = baseUnit == null ? 0 : baseUnit.convert(100, TimeUnit.MILLISECONDS);
    disregardPile.refill();
    while (size > 0) {
      if (timeoutLeft <= 0 && baseUnit != null) {
        // We timed out.
        return false;
      }
      long pollWait = Math.min(timeoutLeft, maxWaitQuantum);
      slot = baseUnit == null ? live.take() : live.poll(pollWait, baseUnit);
      if (slot == poisonPill) {
        slot = baseUnit == null ? live.take() : live.poll(pollWait, baseUnit);
        live.offer(poisonPill);
      }
      timeoutLeft = NanoClock.timeoutLeft(startNanos, timeoutNanos);
      if (slot == null) {
        disregardPile.refill();
        continue;
      }
      if (slot.isDead() || slot.live2dead()) {
        dealloc(slot);
        unregisterWithLeakDetector(slot);
      } else {
        live.offer(slot);
      }
    }
    return true;
  }

  @Override
  synchronized void setTargetSize(long targetSize) {
    if (shutdown) {
      return;
    }
    this.targetSize = targetSize;
    changePoolSize(targetSize);
  }

  @Override
  long getTargetSize() {
    return targetSize;
  }

  @Override
  long getAllocationCount() {
    return allocationCount;
  }

  @Override
  long getFailedAllocationCount() {
    return failedAllocationCount;
  }

  @Override
  long countLeakedObjects() {
    if (leakDetector != null) {
      return leakDetector.countLeakedObjects();
    }
    return -1;
  }

  private void registerWithLeakDetector(BSlot<T> slot) {
    if (leakDetector != null) {
      leakDetector.register(slot);
    }
  }

  private void unregisterWithLeakDetector(BSlot<T> slot) {
    if (leakDetector != null) {
      leakDetector.unregister(slot);
    }
  }

  private void changePoolSize(long targetSize) {
    while (size != targetSize) {
      if (size < targetSize) {
        // Grow the pool.
        allocate();
      } else {
        // Otherwise shrink the pool.
        if (!tryDeallocate()) {
          // Give up if we can't change the pool size without blocking.
          return;
        }
      }
    }
  }

  private void allocate() {
    BSlot<T> slot = optimizeForMemory ?
            new BSlot<>(live, poisonedSlots) : new BSlotPadded<>(live, poisonedSlots);
    alloc(slot);
    registerWithLeakDetector(slot);
  }

  private void alloc(BSlot<T> slot) {
    boolean success = false;
    try {
      slot.obj = allocator.allocate(slot);
      if (slot.obj == null) {
        poisonedSlots.getAndIncrement();
        slot.poison = new NullPointerException("Allocation returned null.");
      } else {
        success = true;
      }
    } catch (Exception e) {
      poisonedSlots.getAndIncrement();
      slot.poison = e;
    }
    long ignore = (long) SIZE.getAndAdd(this, 1L);
    publishSlot(slot, success, NanoClock.nanoTime());
  }

  private void publishSlot(BSlot<T> slot, boolean success, long now) {
    resetSlot(slot, now);
    if (success && !live.hasWaitingConsumer()) {
      // Successful, fresh allocations go to the front of the queue.
      newAllocations.push(slot);
    } else {
      // Failed allocations go to the back of the queue.
      live.offer(slot);
    }
    incrementAllocationCounts(success);
  }

  private void incrementAllocationCounts(boolean success) {
    if (success) {
      long ignore = (long) ALLOC_COUNT.getAndAdd(this, 1L);
    } else {
      long ignore = (long) FAILED_ALLOC_COUNT.getAndAdd(this, 1L);
    }
  }

  private void resetSlot(BSlot<T> slot, long now) {
    slot.createdNanos = now;
    slot.stamp = 0;
    slot.dead2live();
  }

  private boolean tryDeallocate() {
    BSlot<T> slot = live.poll();
    if (slot == null) {
      if (!disregardPile.refill()) {
        newAllocations.refill();
      }
      slot = live.poll();
    }
    boolean firstIteration = true;
    while (slot != null) {
      if (slot.isDead() || slot.live2dead()) {
        dealloc(slot);
        unregisterWithLeakDetector(slot);
        return true;
      }
      if (firstIteration) {
        disregardPile.refill();
        newAllocations.refill();
        firstIteration = false;
      }
      disregardPile.push(slot);
      slot = live.poll();
    }
    return false;
  }

  private void dealloc(BSlot<T> slot) {
    long ignore = (long) SIZE.getAndAdd(this, -1L);
    deallocSlot(slot);
  }

  private void deallocSlot(BSlot<T> slot) {
    try {
      if (slot.poison == BlazePool.EXPLICIT_EXPIRE_POISON) {
        slot.poison = null;
        poisonedSlots.getAndDecrement();
      }
      if (slot.poison == null) {
        recordObjectLifetimeSample(NanoClock.elapsed(slot.createdNanos));
        allocator.deallocate(slot.obj);
      } else {
        poisonedSlots.getAndDecrement();
      }
    } catch (Exception ignore) { // NOPMD
      // Ignored as per specification
    }
    slot.poison = null;
    slot.obj = null;
  }

  private void realloc(BSlot<T> slot) {
    if (slot.poison == BlazePool.EXPLICIT_EXPIRE_POISON) {
      slot.poison = null;
      poisonedSlots.getAndDecrement();
    }
    if (slot.poison == null) {
      boolean success = false;
      try {
        slot.obj = allocator.reallocate(slot, slot.obj);
        if (slot.obj == null) {
          poisonedSlots.getAndIncrement();
          slot.poison = new NullPointerException("Reallocation returned null.");
        } else {
          success = true;
        }
      } catch (Exception e) {
        poisonedSlots.getAndIncrement();
        slot.poison = e;
      }
      long now = NanoClock.nanoTime();
      recordObjectLifetimeSample(now - slot.createdNanos);
      publishSlot(slot, success, now);
    } else {
      dealloc(slot);
      alloc(slot);
    }
  }

  private void recordObjectLifetimeSample(long nanoseconds) {
    if (metricsRecorder != null) {
      long milliseconds = TimeUnit.NANOSECONDS.toMillis(nanoseconds);
      metricsRecorder.recordObjectLifetimeSampleMillis(milliseconds);
    }
  }
  
  @Override
  public long allocatedSize() {
    return live.size() - poisonedSlots.get();
  }

  @Override
  long inUse() {
    long inUse = 0;
    long liveSize = 0;
    for (BSlot<T> slot: live) {
      liveSize++;
      if (slot.isClaimedOrThreadLocal()) {
        inUse++;
      }
    }
    return size - liveSize + inUse;
  } 
}
