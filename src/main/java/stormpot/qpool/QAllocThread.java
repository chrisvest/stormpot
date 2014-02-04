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
package stormpot.qpool;

import stormpot.*;

import java.util.concurrent.BlockingQueue;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.LockSupport;

class QAllocThread<T extends Poolable> extends Thread {
  /**
   * Special slot used to signal that the pool has been shut down.
   */
  final QSlot<T> POISON_PILL = new QSlot<T>(null);

  private static int instanceOrdinal=0;

  private final CountDownLatch completionLatch;
  private final BlockingQueue<QSlot<T>> live;
  private final BlockingQueue<QSlot<T>> dead;
  private final Reallocator<T> allocator;
  private volatile int targetSize;
  private int size;

  public QAllocThread(
      BlockingQueue<QSlot<T>> live, BlockingQueue<QSlot<T>> dead,
      Config<T> config) {
    this.targetSize = config.getSize();
    completionLatch = new CountDownLatch(1);
    this.allocator = config.getReallocator();
    this.size = 0;
    this.live = live;
    this.dead = dead;
    nameThread();
  }

  private synchronized void nameThread() {
      this.setName("qpool-allocator-" + instanceOrdinal++);
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
        long deadPollTimeout = size == targetSize ? 50 : 1;
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
          // Mutation testing might note that the above alloc() call can be
          // removed... that's okay, it's really just an optimisation that
          // prevents us from creating new slots all the time - we reuse them.
        }
      }
    } catch (InterruptedException _) {
      // This means we've been shut down.
      // let the poison-pill enter the system
      live.offer(POISON_PILL);
    }
  }

  private void shutPoolDown() {
    while (size > 0) {
      QSlot<T> slot = dead.poll();
      if (slot == null) {
        slot = live.poll();
      }
      if (slot == POISON_PILL) {
        // FindBugs complains that we ignore a possible exceptional return
        // value from offer(). However, since the queues are unbounded, an
        // offer will never fail.
        live.offer(POISON_PILL);
        slot = null;
      }
      if (slot == null) {
        LockSupport.parkNanos(10000000); // 10 millis
      } else {
        dealloc(slot);
      }
    }
  }

  private void alloc(QSlot<T> slot) {
    try {
      slot.obj = allocator.allocate(slot);
      if (slot.obj == null) {
        slot.poison = new NullPointerException("allocation returned null");
      }
    } catch (Exception e) {
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
          slot.poison = new NullPointerException("reallocation returned null");
        }
      } catch (Exception e) {
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

  boolean await(Timeout timeout) throws InterruptedException {
    return completionLatch.await(timeout.getTimeout(), timeout.getUnit());
  }

  void setTargetSize(int size) {
    this.targetSize = size;
    LockSupport.unpark(this);
    // Mutation testing will note, that the above call to unpark can be removed.
    // That's okay, because it is only an optimisation to speed up the
    // allocators reaction to the new size.
  }

  int getTargetSize() {
    return targetSize;
  }
}
