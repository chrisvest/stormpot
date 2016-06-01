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
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.LinkedTransferQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.locks.LockSupport;

final class BAllocThread<T extends Poolable> implements Runnable {
  /**
   * The amount of time, in nanoseconds, to wait for more work when the
   * shutdown process has deallocated all the dead and live slots it could
   * get its hands on, but there are still (claimed) slots left.
   */
  private final static long shutdownPauseNanos =
      TimeUnit.MILLISECONDS.toNanos(10);

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

  // Single reader: this. Many writers.
  private volatile int targetSize;
  private volatile boolean shutdown;

  // Many readers. Single writer: this.
  private volatile long allocationCount;
  private volatile long failedAllocationCount;

  private int size;
  private boolean didAnythingLastIteration;

  public BAllocThread(
      BlockingQueue<BSlot<T>> live,
      DisregardBPile<T> disregardPile,
      Config<T> config,
      BSlot<T> poisonPill) {
    this.live = live;
    this.disregardPile = disregardPile;
    this.allocator = config.getAdaptedReallocator();
    this.targetSize = config.getSize();
    this.metricsRecorder = config.getMetricsRecorder();
    this.poisonPill = poisonPill;
    this.expiration = config.getExpiration();
    this.backgroundExpirationEnabled = config.isBackgroundExpirationEnabled();
    this.leakDetector = config.isPreciseLeakDetectionEnabled() ?
        new PreciseLeakDetector() : null;
    this.completionLatch = new CountDownLatch(1);
    this.dead = new LinkedTransferQueue<>();
    this.poisonedSlots = new AtomicInteger();
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
    boolean weHaveWorkToDo = size != targetSize || poisonedSlots.get() > 0;
    long deadPollTimeout = weHaveWorkToDo?
        (didAnythingLastIteration? 0 : 10) : 50;
    didAnythingLastIteration = false;
    if (size < targetSize) {
      increaseSizeByAllocating();
    }
    BSlot<T> slot = dead.poll(deadPollTimeout, TimeUnit.MILLISECONDS);
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
    } else if (backgroundExpirationEnabled && !weHaveWorkToDo) {
      backgroundExpirationCheck();
    }
  }

  private void increaseSizeByAllocating() {
    BSlot<T> slot = new BSlot<>(live, poisonedSlots);
    alloc(slot);
    registerWithLeakDetector(slot);
  }

  private void reduceSizeByDeallocating(BSlot<T> slot) {
    slot = slot == null? live.poll() : slot;
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
    try {
      slot.obj = allocator.allocate(slot);
      if (slot.obj == null) {
        poisonedSlots.getAndIncrement();
        failedAllocationCount++;
        slot.poison = new NullPointerException("Allocation returned null");
      } else {
        allocationCount++;
      }
    } catch (Exception e) {
      poisonedSlots.getAndIncrement();
      failedAllocationCount++;
      slot.poison = e;
    }
    size++;
    resetSlot(slot, System.currentTimeMillis());
    live.offer(slot);
    didAnythingLastIteration = true;
  }

  private void resetSlot(BSlot<T> slot, long now) {
    slot.created = now;
    slot.claims = 0;
    slot.stamp = 0;
    slot.dead2live();
  }

  private void dealloc(BSlot<T> slot) {
    size--;
    try {
      if (slot.poison == null) {
        long now = System.currentTimeMillis();
        recordObjectLifetimeSample(now - slot.created);
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
      try {
        slot.obj = allocator.reallocate(slot, slot.obj);
        if (slot.obj == null) {
          poisonedSlots.getAndIncrement();
          failedAllocationCount++;
          slot.poison = new NullPointerException("Reallocation returned null");
        } else {
          allocationCount++;
        }
      } catch (Exception e) {
        poisonedSlots.getAndIncrement();
        failedAllocationCount++;
        slot.poison = e;
      }
      long now = System.currentTimeMillis();
      recordObjectLifetimeSample(now - slot.created);
      resetSlot(slot, now);
      live.offer(slot);
    } else {
      dealloc(slot);
      alloc(slot);
    }
    didAnythingLastIteration = true;
  }

  private void recordObjectLifetimeSample(long milliseconds) {
    if (metricsRecorder != null) {
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
