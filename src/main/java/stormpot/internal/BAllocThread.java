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

import stormpot.Allocator;
import stormpot.Completion;
import stormpot.Expiration;
import stormpot.MetricsRecorder;
import stormpot.Poolable;
import stormpot.Reallocator;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.LinkedTransferQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.locks.LockSupport;

import static java.util.concurrent.TimeUnit.MILLISECONDS;

/**
 * The dedicated background allocation process of a pool that operates in the
 * {@link AllocationProcessMode#THREADED} mode.
 *
 * @param <T> The concrete poolable type.
 */
@SuppressWarnings("NonAtomicOperationOnVolatileField")
public final class BAllocThread<T extends Poolable> implements Runnable {
  /**
   * The amount of time, in nanoseconds, to wait for more work when the
   * shutdown process has deallocated all the dead and live slots it could
   * get its hands on, but there are still (claimed) slots left.
   */
  private static final long shutdownPauseNanos = MILLISECONDS.toNanos(10);

  private final LinkedTransferQueue<BSlot<T>> live;
  private final RefillPile<T> disregardPile;
  private final RefillPile<T> newAllocations;
  private final BSlot<T> poisonPill;
  private final MetricsRecorder metricsRecorder;
  private final Expiration<? super T> expiration;
  private final boolean backgroundExpirationEnabled;
  private final PreciseLeakDetector leakDetector;
  private final StackCompletion shutdownCompletion;
  private final LinkedTransferQueue<Task> dead;
  private final AtomicLong poisonedSlots;
  private final long defaultDeadPollTimeout;
  private final boolean optimizeForMemory;
  private final LinkedTransferQueue<AllocatorSwitch<T>> switchRequests;

  // Single reader: this. Many writers.
  private volatile long targetSize;
  private volatile boolean shutdown;

  // Many readers. Single writer: this.
  private volatile long allocationCount;
  private volatile long failedAllocationCount;

  private Reallocator<T> allocator;
  private long size;
  private boolean didAnythingLastIteration;
  private long consecutiveAllocationFailures;
  private AllocatorSwitch<T> nextAllocator;
  private long priorGenerationObjectsToReplace;

  BAllocThread(
      LinkedTransferQueue<BSlot<T>> live,
      RefillPile<T> disregardPile,
      RefillPile<T> newAllocations,
      PoolBuilderImpl<T> builder,
      BSlot<T> poisonPill) {
    this.live = live;
    this.disregardPile = disregardPile;
    this.newAllocations = newAllocations;
    this.allocator = builder.getAdaptedReallocator();
    this.targetSize = builder.getSize();
    this.metricsRecorder = builder.getMetricsRecorder();
    this.poisonPill = poisonPill;
    this.expiration = builder.getExpiration();
    this.backgroundExpirationEnabled = builder.isBackgroundExpirationEnabled();
    this.leakDetector = builder.isPreciseLeakDetectionEnabled() ?
        new PreciseLeakDetector() : null;
    this.shutdownCompletion = new StackCompletion();
    this.dead = new LinkedTransferQueue<>();
    this.poisonedSlots = new AtomicLong();
    this.defaultDeadPollTimeout = builder.getBackgroundExpirationCheckDelay();
    this.optimizeForMemory = builder.isOptimizeForReducedMemoryUsage();
    switchRequests = new LinkedTransferQueue<>();
    this.size = 0;
    this.didAnythingLastIteration = true; // start out busy
  }

  @Override
  public void run() {
    continuouslyReplenishPool();
    shutPoolDown();
    shutdownCompletion.complete();
  }

  private void continuouslyReplenishPool() {
    try {
      while (!shutdown) {
        replenishPool();
      }
    } catch (InterruptedException ignore) {
      // This can only be thrown by the dead.poll() method call, because alloc
      // catches exceptions and use them for poison.
    }
    // This means we've been shut down.
    // let the poison-pill enter the system
    poisonPill.dead2live();
    live.offer(poisonPill);
  }

  private void replenishPool() throws InterruptedException {
    long deadPollTimeout = computeDeadPollTimeout();
    Task task = deadPollTimeout == 0 ? dead.poll() : dead.poll(deadPollTimeout, MILLISECONDS);
    checkForAllocatorSwitch();
    if (size < targetSize) {
      increaseSizeByAllocating();
    }
    if (task instanceof BSlot<?> slot) {
      if (size > targetSize) {
        reduceSizeByDeallocating((BSlot<T>) slot);
      } else if (slot != null) {
        reallocateDeadSlot((BSlot<T>) slot);
      }
    } else if (size > targetSize) {
      reduceSizeByDeallocating(null);
    }
    if (leakDetector != null) {
      // Make sure we process any cleared references, so the reference queue don't get too big.
      leakDetector.countLeakedObjects();
    }

    if (shutdown) {
      // Prior allocations might notice that we've been shut down. In that
      // case, we need to skip the eager reallocation of poisoned slots.
      return;
    }

    if (poisonedSlots.get() > 0) {
      // Proactively seek out and try to heal poisoned slots
      proactivelyHealPoison();
    } else if (priorGenerationObjectsToReplace > 0 ||
            backgroundExpirationEnabled && size == targetSize) {
      backgroundCheck();
    }
  }

  private void checkForAllocatorSwitch() {
    List<StackCompletion> skippedCompletions = null;
    AllocatorSwitch<T> newSwitch, previous = nextAllocator;
    while ((newSwitch = switchRequests.poll()) != null) {
      if (previous != null) {
        if (skippedCompletions == null) {
          skippedCompletions = new ArrayList<>();
        }
        skippedCompletions.add(previous.completion());
      }
      previous = newSwitch;
    }
    if (previous != null && skippedCompletions != null) {
      List<StackCompletion> skipped = skippedCompletions;
      previous.completion().subscribe(new BaseSubscriber() {
        @Override
        public void onComplete() {
          for (StackCompletion completion : skipped) {
            completion.complete();
          }
        }
      });
    }
    if (previous != nextAllocator) {
      priorGenerationObjectsToReplace = size;
      allocator = previous.allocator();
      nextAllocator = previous;
    }
  }

  private long computeDeadPollTimeout() {
    // Default timeout.
    long deadPollTimeout = defaultDeadPollTimeout;
    if (size != targetSize || poisonedSlots.get() > 0) {
      // Make timeout shorter if we have work piled up.
      deadPollTimeout = (didAnythingLastIteration ? 0 : 10);
      // Unless we have a lot of allocation failures.
      // In that case, make the timeout longer to avoid wasting CPU.
      deadPollTimeout += Math.min(consecutiveAllocationFailures,
          defaultDeadPollTimeout - deadPollTimeout);
    }
    didAnythingLastIteration = false;
    return deadPollTimeout;
  }

  private void increaseSizeByAllocating() {
    BSlot<T> slot = optimizeForMemory ?
            new BSlot<>(live, poisonedSlots) : new BSlotPadded<>(live, poisonedSlots);
    alloc(slot);
    registerWithLeakDetector(slot);
  }

  private void reduceSizeByDeallocating(BSlot<T> slot) {
    if (slot == null || !didAnythingLastIteration) {
      disregardPile.refill();
      newAllocations.refill();
    }
    slot = slot == null ? live.poll() : slot;
    if (slot != null) {
      if (slot.isDead() || slot.live2dead()) {
        dealloc(slot);
        unregisterWithLeakDetector(slot);
      } else {
        live.offer(slot);
      }
    }
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

  private void reallocateDeadSlot(BSlot<T> slot) {
    realloc(slot);
  }

  private void proactivelyHealPoison() {
    BSlot<T> slot = live.poll();
    if (slot != null) {
      if (slot.poison != null && (slot.isDead() || slot.live2dead())) {
        realloc(slot);
      } else {
        live.offer(slot);
      }
    }
  }

  private void backgroundCheck() {
    disregardPile.refill();
    if (!didAnythingLastIteration) {
      newAllocations.refill();
    }
    BSlot<T> slot = live.poll();
    if (slot == null) {
      newAllocations.refill();
      slot = live.poll();
    }
    if (slot != null) {
      if (slot.isLive() && slot.live2claim()) {
        boolean expired;
        try {
          expired = slot.poison != null || expiration.hasExpired(slot) || slot.allocator != allocator;
        } catch (Exception ignore) {
          expired = true;
        }
        if (expired) {
          slot.claim2dead(); // Not strictly necessary
          dead.offer(slot);
          didAnythingLastIteration = true;
        } else {
          slot.claim2live();
          live.offer(slot);
        }
      } else {
        live.offer(slot);
      }
    }
  }

  private void shutPoolDown() {
    while (size > 0) {
      BSlot<T> slot;
      Task task = dead.poll();
      if (task instanceof BSlot<?> bSlot) {
        slot = (BSlot<T>) bSlot;
      } else if (task == null) {
        slot = live.poll();
      } else {
        slot = null;
      }
      if (slot == poisonPill) {
        live.offer(poisonPill);
        slot = null;
      }
      if (slot == null) {
        if (!disregardPile.refill() && !newAllocations.refill()) {
          LockSupport.parkNanos(shutdownPauseNanos);
        }
      } else {
        if (slot.isDead() || slot.live2dead()) {
          dealloc(slot);
          unregisterWithLeakDetector(slot);
        } else {
          live.offer(slot);
        }
      }
    }
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
    size++;
    publishSlot(slot, success, NanoClock.nanoTime());
    didAnythingLastIteration = true;
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
      allocationCount++;
      consecutiveAllocationFailures = 0;
    } else {
      failedAllocationCount++;
      consecutiveAllocationFailures++;
    }
  }

  private void resetSlot(BSlot<T> slot, long now) {
    slot.createdNanos = now;
    slot.stamp = 0;
    slot.allocator = allocator;
    slot.dead2live();
  }

  private void dealloc(BSlot<T> slot) {
    size--;
    try {
      if (slot.poison == BlazePool.EXPLICIT_EXPIRE_POISON) {
        slot.poison = null;
        poisonedSlots.getAndDecrement();
      }
      if (slot.poison == null) {
        recordObjectLifetimeSample(NanoClock.elapsed(slot.createdNanos));
        slot.allocator.deallocate(slot.obj);
      } else {
        poisonedSlots.getAndDecrement();
      }
    } catch (Exception ignore) { // NOPMD
      // Ignored as per specification
    }
    slot.poison = null;
    slot.obj = null;
    didAnythingLastIteration = true;
    if (slot.allocator != allocator) {
      replacedPriorGenerationSlot();
    }
  }

  private void realloc(BSlot<T> slot) {
    if (slot.poison == BlazePool.EXPLICIT_EXPIRE_POISON) {
      slot.poison = null;
      poisonedSlots.getAndDecrement();
    }
    if (slot.poison == null && nextAllocator == null) {
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
    didAnythingLastIteration = true;
  }

  private void replacedPriorGenerationSlot() {
    if (--priorGenerationObjectsToReplace == 0) {
      nextAllocator.completion().complete();
      nextAllocator = null;
    }
  }

  private void recordObjectLifetimeSample(long nanoseconds) {
    if (metricsRecorder != null) {
      long milliseconds = TimeUnit.NANOSECONDS.toMillis(nanoseconds);
      metricsRecorder.recordObjectLifetimeSampleMillis(milliseconds);
    }
  }

  void setTargetSize(long size) {
    this.targetSize = size;
  }

  long getTargetSize() {
    return targetSize;
  }

  Completion shutdown(Thread allocatorThread) {
    shutdown = true;
    allocatorThread.interrupt();
    return shutdownCompletion;
  }

  Completion switchAllocator(Allocator<T> replacementAllocator) {
    StackCompletion completion = new StackCompletion();
    Reallocator<T> reallocator = ReallocatingAdaptor.adapt(replacementAllocator, metricsRecorder);
    AllocatorSwitch<T> switchRequest = new AllocatorSwitch<>(completion, reallocator);
    if (shutdown) {
      AllocatorSwitch<T> entry;
      while ((entry = switchRequests.poll()) != null) {
        entry.completion().complete();
      }
    } else {
      shutdownCompletion.propagateTo(completion);
      switchRequests.offer(switchRequest);
    }
    return completion;
  }

  long getAllocationCount() {
    return allocationCount;
  }

  long getFailedAllocationCount() {
    return failedAllocationCount;
  }

  long countLeakedObjects() {
    if (leakDetector != null) {
      return leakDetector.countLeakedObjects();
    }
    return -1;
  }

  void offerDeadSlot(BSlot<T> slot) {
    dead.offer(slot);
  }
  
  long allocatedSize() {
    return size;
  }
  
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
