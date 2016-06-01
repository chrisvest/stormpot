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
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.locks.LockSupport;

final class QAllocThread<T extends Poolable> implements Runnable {
  /**
   * The amount of time, in nanoseconds, to wait for more work when the
   * shutdown process has deallocated all the dead and live slots it could
   * get its hands on, but there are still (claimed) slots left.
   */
  private final static long shutdownPauseNanos =
      TimeUnit.MILLISECONDS.toNanos(10);

  private final CountDownLatch completionLatch;
  private final BlockingQueue<QSlot<T>> live;
  private final BlockingQueue<QSlot<T>> dead;
  private final Reallocator<T> allocator;
  private final QSlot<T> poisonPill;
  private final MetricsRecorder metricsRecorder;
  private final boolean backgroundExpirationEnabled;
  private final Expiration<? super T> expiration;
  private final PreciseLeakDetector leakDetector;
  private final AtomicInteger poisonedSlots;

  // Single reader: this. Many writers.
  private volatile int targetSize;
  private volatile boolean shutdown;

  // Many readers. Single writer: this.
  private volatile long allocationCount;
  private volatile long failedAllocationCount;

  private int size;
  private boolean didAnythingLastIteration;

  public QAllocThread(
      BlockingQueue<QSlot<T>> live,
      BlockingQueue<QSlot<T>> dead,
      Config<T> config,
      QSlot<T> poisonPill) {
    this.targetSize = config.getSize();
    completionLatch = new CountDownLatch(1);
    this.allocator = config.getAdaptedReallocator();
    this.size = 0;
    this.didAnythingLastIteration = true; // start out busy
    this.live = live;
    this.dead = dead;
    this.poisonPill = poisonPill;
    this.metricsRecorder = config.getMetricsRecorder();
    this.backgroundExpirationEnabled = config.isBackgroundExpirationEnabled();
    this.expiration = config.getExpiration();
    this.leakDetector = config.isPreciseLeakDetectionEnabled() ?
        new PreciseLeakDetector() : null;
    this.poisonedSlots = new AtomicInteger();
  }

  @Override
  public void run() {
    continuouslyReplenishPool();
    shutPoolDown();
    completionLatch.countDown();
  }

  private void continuouslyReplenishPool() {
    try {
      //noinspection InfiniteLoopStatement
      for (;;) {
        boolean weHaveWorkToDo = size != targetSize || poisonedSlots.get() > 0;
        long deadPollTimeout = weHaveWorkToDo?
            (didAnythingLastIteration? 0 : 10) : 50;
        didAnythingLastIteration = false;
        if (size < targetSize) {
          QSlot<T> slot = new QSlot<T>(live, poisonedSlots);
          alloc(slot);
          registerWithLeakDetector(slot);
          didAnythingLastIteration = true;
        }
        QSlot<T> slot = dead.poll(deadPollTimeout, TimeUnit.MILLISECONDS);
        if (size > targetSize) {
          slot = slot == null? live.poll() : slot;
          if (slot != null) {
            dealloc(slot);
            unregisterWithLeakDetector(slot);
            didAnythingLastIteration = true;
          }
        } else if (slot != null) {
          realloc(slot);
          didAnythingLastIteration = true;
        }

        if (shutdown) {
          break;
        }

        if (poisonedSlots.get() > 0) {
          // Proactively seek out and try to heal poisoned slots
          slot = live.poll();
          if (slot != null) {
            if (slot.poison == null && !slot.expired) {
              live.offer(slot);
            } else {
              realloc(slot);
              didAnythingLastIteration = true;
            }
          }
        } else if (backgroundExpirationEnabled && !weHaveWorkToDo) {
          slot = live.poll();
          try {
            if (slot != null) {
              if (slot.expired || expiration.hasExpired(slot)) {
                dead.offer(slot);
                didAnythingLastIteration = true;
              } else {
                live.offer(slot);
              }
            }
          } catch (Exception e) {
            dead.offer(slot);
            didAnythingLastIteration = true;
          }
        }
      }
    } catch (InterruptedException ignore) {
      // This can only be thrown by the dead.poll() method call, because alloc
      // catches exceptions and use them for poison.
    }
    // This means we've been shut down.
    // let the poison-pill enter the system
    live.offer(poisonPill);
  }

  private void registerWithLeakDetector(QSlot<T> slot) {
    if (leakDetector != null) {
      leakDetector.register(slot);
    }
  }

  private void unregisterWithLeakDetector(QSlot<T> slot) {
    if (leakDetector != null) {
      leakDetector.unregister(slot);
    }
  }

  private void shutPoolDown() {
    while (size > 0) {
      QSlot<T> slot = dead.poll();
      if (slot == null) {
        slot = live.poll();
      }
      if (slot == poisonPill) {
        live.offer(poisonPill);
        slot = null;
      }
      if (slot == null) {
        LockSupport.parkNanos(shutdownPauseNanos);
      } else {
        dealloc(slot);
        unregisterWithLeakDetector(slot);
      }
    }
  }

  private void alloc(QSlot<T> slot) {
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
    slot.created = System.currentTimeMillis();
    slot.claims = 0;
    slot.stamp = 0;
    slot.expired = false;
    slot.claimed.set(true);
    slot.release(slot.obj);
  }

  private void dealloc(QSlot<T> slot) {
    size--;
    try {
      if (slot.poison == null) {
        recordObjectLifetimeSample(System.currentTimeMillis() - slot.created);
        allocator.deallocate(slot.obj);
        if (slot.expired) {
          poisonedSlots.getAndDecrement();
        }
      } else {
        poisonedSlots.getAndDecrement();
      }
    } catch (Exception ignore) { // NOPMD
      // Ignored as per specification
    }
    slot.poison = null;
    slot.obj = null;
  }

  private void realloc(QSlot<T> slot) {
    if (slot.poison == null) {
      if (slot.expired) {
        poisonedSlots.getAndDecrement();
      }
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
      slot.created = now;
      slot.claims = 0;
      slot.stamp = 0;
      slot.expired = false;
      live.offer(slot);
    } else {
      dealloc(slot);
      alloc(slot);
    }
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

  LatchCompletion shutdown(Thread allocatorThread) {
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
    if (leakDetector !=null) {
      return leakDetector.countLeakedObjects();
    }
    return -1;
  }
}
