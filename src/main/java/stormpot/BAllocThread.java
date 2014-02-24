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

class BAllocThread<T extends Poolable> extends Thread {
  /**
   * The amount of time, in nanoseconds, to wait for more work when the
   * shutdown process has deallocated all the dead and live slots it could
   * get its hands on, but there are still (claimed) slots left.
   */
  private final static long shutdownPauseNanos =
      TimeUnit.MILLISECONDS.toNanos(10);

  
  private final CountDownLatch completionLatch;
  private final BlockingQueue<BSlot<T>> live;
  private final BlockingQueue<BSlot<T>> dead;
  private final Reallocator<T> allocator;
  private final BSlot<T> poisonPill;
  private volatile int targetSize;
  private volatile boolean shutdown;
  private int size;
  private int poisonedSlots;

  public BAllocThread(
      BlockingQueue<BSlot<T>> live,
      BlockingQueue<BSlot<T>> dead,
      Config<T> config, BSlot<T> poisonPill) {
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
      while (!shutdown) {
        replenishPool();
      }
    } catch (InterruptedException ignore) {
    }
    // This means we've been shut down.
    // let the poison-pill enter the system
    poisonPill.dead2live();
    live.offer(poisonPill);
  }

  private void replenishPool() throws InterruptedException {
    boolean weHaveWorkToDo = size != targetSize || poisonedSlots > 0;
    long deadPollTimeout = weHaveWorkToDo? 0 : 50;
    if (size < targetSize) {
      BSlot<T> slot = new BSlot<T>(live);
      alloc(slot);
    }
    BSlot<T> slot = dead.poll(deadPollTimeout, TimeUnit.MILLISECONDS);
    if (size > targetSize) {
      slot = slot == null? live.poll() : slot;
      if (slot != null) {
        if (slot.isDead() || slot.live2dead()) {
          dealloc(slot);
        } else {
          live.offer(slot);
        }
      }
    } else if (slot != null) {
      realloc(slot);
    }

    if (shutdown) {
      // Prior allocations might notice that we've been shut down. In that
      // case, we need to skip the eager reallocation of poisoned slots.
      return;
    }

    if (poisonedSlots > 0) {
      // Proactively seek out and try to heal poisoned slots
      slot = live.poll();
      if (slot != null) {
                                                // TODO test for this
        if ((slot.isDead() || slot.live2dead()) /* && slot.poison != null */) {
          realloc(slot);
        } else {
          live.offer(slot);
        }
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
        // FindBugs complains that we ignore a possible exceptional return
        // value from offer(). However, since the queues are unbounded, an
        // offer will never fail.
        live.offer(poisonPill);
        slot = null;
      }
      if (slot == null) {
        LockSupport.parkNanos(shutdownPauseNanos);
      } else {
        if (slot.isDead() || slot.live2dead()) {
          dealloc(slot);
        } else {
          live.offer(slot);
        }
      }
    }
  }

  private void alloc(BSlot<T> slot) {
    assert slot != poisonPill : "Cannot allocate the poison pill: " + slot;
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
    slot.dead2live();
    live.offer(slot);
  }

  private void dealloc(BSlot<T> slot) {
    assert slot.isDead() : "Cannot deallocate non-dead slot: " + slot;
    assert slot != poisonPill : "Cannot deallocate the poison pill: " + slot;
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

  private void realloc(BSlot<T> slot) {
    assert slot.isDead() : "Cannot reallocate non-dead slot: " + slot;
    assert slot != poisonPill : "Cannot reallocate the poison pill: " + slot;
    if (slot.poison == null) {
      assert slot.obj != null : "slot.obj should not have been null";
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
      slot.dead2live();
      live.offer(slot);
    } else {
      dealloc(slot);
      alloc(slot);
    }
  }

  boolean await(Timeout timeout) throws InterruptedException {
    return completionLatch.await(timeout.getTimeout(), timeout.getUnit());
  }

  void setTargetSize(int size) {
    this.targetSize = size;
  }

  int getTargetSize() {
    return targetSize;
  }

  void shutdown() {
    shutdown = true;
    interrupt();
  }
}
