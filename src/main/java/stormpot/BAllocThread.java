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
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.LinkedTransferQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.locks.LockSupport;

import static java.util.concurrent.TimeUnit.MILLISECONDS;

@SuppressWarnings("NonAtomicOperationOnVolatileField")
final class BAllocThread<T extends Poolable> implements Runnable {
  /**
   * The amount of time, in nanoseconds, to wait for more work when the
   * shutdown process has deallocated all the dead and live slots it could
   * get its hands on, but there are still (claimed) slots left.
   */
  private static final long shutdownPauseNanos = MILLISECONDS.toNanos(10);

  private final BlockingQueue<BSlot<T>> live;
  private final DisregardBPile<T> disregardPile;
  private final Reallocator<T> allocator;
  private final BSlot<T> poisonPill;
  private final MetricsRecorder metricsRecorder;
  private final Expiration<? super T> expiration;
  private final boolean backgroundExpirationEnabled;
  private final PreciseLeakDetector leakDetector;
  private final CountDownLatch completionLatch;
  private final BlockingQueue<BSlot<T>> dead;
  private final AtomicInteger poisonedSlots;
  private final long defaultDeadPollTimeout;

  // Single reader: this. Many writers.
  private volatile int targetSize;
  private volatile boolean shutdown;

  // Many readers. Single writer: this.
  private volatile long allocationCount;
  private volatile long failedAllocationCount;

  private int size;
  private boolean didAnythingLastIteration;
  private long consecutiveAllocationFailures;

  BAllocThread(
      BlockingQueue<BSlot<T>> live,
      DisregardBPile<T> disregardPile,
      PoolBuilder<T> builder,
      BSlot<T> poisonPill) {
    this.live = live;
    this.disregardPile = disregardPile;
    this.allocator = builder.getAdaptedReallocator();
    this.targetSize = builder.getSize();
    this.metricsRecorder = builder.getMetricsRecorder();
    this.poisonPill = poisonPill;
    this.expiration = builder.getExpiration();
    this.backgroundExpirationEnabled = builder.isBackgroundExpirationEnabled();
    this.leakDetector = builder.isPreciseLeakDetectionEnabled() ?
        new PreciseLeakDetector() : null;
    this.completionLatch = new CountDownLatch(1);
    this.dead = new LinkedTransferQueue<>();
    this.poisonedSlots = new AtomicInteger();
    this.defaultDeadPollTimeout = 100;
    this.size = 0;
    this.didAnythingLastIteration = true; // start out busy
  }

  @Override
  public void run() {
    continuouslyReplenishPool();
    shutPoolDown();
    completionLatch.countDown();
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
    BSlot<T> slot = dead.poll(deadPollTimeout, MILLISECONDS);
    if (size < targetSize) {
      increaseSizeByAllocating();
    }
    if (size > targetSize) {
      reduceSizeByDeallocating(slot);
    } else if (slot != null) {
      reallocateDeadSlot(slot);
    }

    if (shutdown) {
      // Prior allocations might notice that we've been shut down. In that
      // case, we need to skip the eager reallocation of poisoned slots.
      return;
    }

    if (poisonedSlots.get() > 0) {
      // Proactively seek out and try to heal poisoned slots
      proactivelyHealPoison();
    } else if (backgroundExpirationEnabled && size == targetSize) {
      backgroundExpirationCheck();
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
    BSlot<T> slot = new BSlot<>(live, poisonedSlots);
    alloc(slot);
    registerWithLeakDetector(slot);
  }

  private void reduceSizeByDeallocating(BSlot<T> slot) {
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

  private void backgroundExpirationCheck() {
    disregardPile.refillQueue();
    BSlot<T> slot = live.poll();
    if (slot != null) {
      if (slot.isLive() && slot.live2claim()) {
        boolean expired;
        try {
          expired = slot.poison != null || expiration.hasExpired(slot);
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
      BSlot<T> slot = dead.poll();
      if (slot == null) {
        slot = live.poll();
      }
      if (slot == poisonPill) {
        live.offer(poisonPill);
        slot = null;
      }
      if (slot == null) {
        if (!disregardPile.refillQueue()) {
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
        slot.poison = new NullPointerException("Allocation returned null");
      } else {
        success = true;
      }
    } catch (Exception e) {
      poisonedSlots.getAndIncrement();
      slot.poison = e;
    }
    size++;
    resetSlot(slot, System.nanoTime());
    incrementAllocationCounts(success);
    live.offer(slot);
    didAnythingLastIteration = true;
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
    slot.claims = 0;
    slot.stamp = 0;
    slot.dead2live();
  }

  private void dealloc(BSlot<T> slot) {
    size--;
    try {
      if (slot.poison == null) {
        long now = System.nanoTime();
        recordObjectLifetimeSample(now - slot.createdNanos);
        allocator.deallocate(slot.obj);
      } else {
        poisonedSlots.getAndDecrement();
      }
    } catch (Exception ignore) { // NOPMD
      // Ignored as per specification
    }
    slot.poison = null;
    slot.obj = null;
    didAnythingLastIteration = true;
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
          slot.poison = new NullPointerException("Reallocation returned null");
        } else {
          success = true;
        }
      } catch (Exception e) {
        poisonedSlots.getAndIncrement();
        slot.poison = e;
      }
      long now = System.nanoTime();
      recordObjectLifetimeSample(now - slot.createdNanos);
      resetSlot(slot, now);
      incrementAllocationCounts(success);
      live.offer(slot);
    } else {
      dealloc(slot);
      alloc(slot);
    }
    didAnythingLastIteration = true;
  }

  private void recordObjectLifetimeSample(long nanoseconds) {
    if (metricsRecorder != null) {
      long milliseconds = TimeUnit.NANOSECONDS.toMillis(nanoseconds);
      metricsRecorder.recordObjectLifetimeSampleMillis(milliseconds);
    }
  }

  void setTargetSize(int size) {
    this.targetSize = size;
  }

  int getTargetSize() {
    return targetSize;
  }

  Completion shutdown(Thread allocatorThread) {
    shutdown = true;
    allocatorThread.interrupt();
    return new LatchCompletion(completionLatch);
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
}
