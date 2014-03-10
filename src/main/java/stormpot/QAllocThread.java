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
import java.util.concurrent.locks.LockSupport;

class QAllocThread<T extends Poolable> extends Thread {
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
  private volatile int targetSize;
  private volatile boolean shutdown;
  private int size;
  private int poisonedSlots;

  public QAllocThread(
      BlockingQueue<QSlot<T>> live, BlockingQueue<QSlot<T>> dead,
      Config<T> config, QSlot<T> poisonPill) {
    this.targetSize = config.getSize();
    completionLatch = new CountDownLatch(1);
    this.allocator = config.getReallocator();
    this.size = 0;
    this.live = live;
    this.dead = dead;
    this.poisonPill = poisonPill;
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
        boolean weHaveWorkToDo = size != targetSize || poisonedSlots > 0;
        long deadPollTimeout = weHaveWorkToDo? 0 : 50;
        if (size < targetSize) {
          QSlot<T> slot = new QSlot<T>(live);
          alloc(slot);
        }
        QSlot<T> slot = dead.poll(deadPollTimeout, TimeUnit.MILLISECONDS);
        if (size > targetSize) {
          slot = slot == null? live.poll() : slot;
          if (slot != null) {
            dealloc(slot);
          }
        } else if (slot != null) {
          realloc(slot);
        }

        if (shutdown) {
          break;
        }

        if (poisonedSlots > 0) {
          // Proactively seek out and try to heal poisoned slots
          slot = live.poll();
          if (slot != null) {
            if (slot.poison == null) {
              live.offer(slot);
            } else {
              realloc(slot);
            }
          }
        }
      }
    } catch (InterruptedException ignore) {
    }
    // This means we've been shut down.
    // let the poison-pill enter the system
    live.offer(poisonPill);
  }

  private void shutPoolDown() {
    while (size > 0) {
      QSlot<T> slot = dead.poll();
      if (slot == null) {
        slot = live.poll();
      }
      if (slot == poisonPill) {
        // FindBugs complains that we ignore a possible exceptional return
        // value from offer(). However, since the queues are unbounded, an
        // offer will never fail.
        live.offer(poisonPill);
        slot = null;
      }
      if (slot == null) {
        LockSupport.parkNanos(shutdownPauseNanos);
      } else {
        dealloc(slot);
      }
    }
  }

  private void alloc(QSlot<T> slot) {
    try {
      slot.obj = allocator.allocate(slot);
      if (slot.obj == null) {
        poisonedSlots++;
        slot.poison = new NullPointerException("allocation returned null");
      }
    } catch (Exception e) {
      poisonedSlots++;
      slot.poison = e;
    }
    size++;
    slot.created = System.currentTimeMillis();
    slot.claims = 0;
    slot.stamp = 0;
    slot.claimed.set(true);
    slot.release(slot.obj);
  }

  private void dealloc(QSlot<T> slot) {
    size--;
    try {
      if (slot.poison == null) {
        allocator.deallocate(slot.obj);
      } else {
        poisonedSlots--;
      }
    } catch (Exception _) { // NOPMD
      // Ignored as per specification
    }
    slot.poison = null;
    slot.obj = null;
  }

  private void realloc(QSlot<T> slot) {
    if (slot.poison == null) {
      try {
        slot.obj = allocator.reallocate(slot, slot.obj);
        if (slot.obj == null) {
          poisonedSlots++;
          slot.poison = new NullPointerException("reallocation returned null");
        }
      } catch (Exception e) {
        poisonedSlots++;
        slot.poison = e;
      }
      slot.created = System.currentTimeMillis();
      slot.claims = 0;
      slot.stamp = 0;
      live.offer(slot);
    } else {
      dealloc(slot);
      alloc(slot);
    }
  }

  void setTargetSize(int size) {
    this.targetSize = size;
  }

  int getTargetSize() {
    return targetSize;
  }

  LatchCompletion shutdown() {
    shutdown = true;
    interrupt();
    return new LatchCompletion(completionLatch);
  }
}
