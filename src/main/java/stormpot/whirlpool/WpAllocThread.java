/*
 * Copyright 2011 Chris Vest
 * 
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 * 
 *      http://www.apache.org/licenses/LICENSE-2.0
 * 
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package stormpot.whirlpool;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import stormpot.Allocator;
import stormpot.Completion;
import stormpot.Config;
import stormpot.Poolable;

@SuppressWarnings("unchecked")
class WpAllocThread extends Thread implements Completion {

  private final CountDownLatch shutdownLatch;
  private final int targetSize;
  private final Allocator allocator;
  private final Whirlpool pool;
  
  private volatile boolean runnable = true;
  
  private int size;
  private long relieveTimeout = 0;
  private TimeUnit relieveUnit = TimeUnit.MILLISECONDS;

  public WpAllocThread(Config config, Whirlpool whirlpool) {
    super("Whirlpool-Allocator-Thread for " + config.getAllocator());
    shutdownLatch = new CountDownLatch(1);
    targetSize = config.getSize();
    allocator = config.getAllocator();
    pool = whirlpool;
  }

  public void shutdown() {
    runnable = false;
  }

  public void await() throws InterruptedException {
    shutdownLatch.await();
  }

  public boolean await(long timeout, TimeUnit unit) throws InterruptedException {
    if (unit == null) {
      throw new IllegalArgumentException("timeout TimeUnit cannot be null");
    }
    return shutdownLatch.await(timeout, unit);
  }

  @Override
  public void run() {
    while (runnable) {
      reallocate();
    }
    // we've been shut down
    deallocateAll();
    shutdownLatch.countDown();
  }

  private void reallocate() {
    if (size < targetSize) {
      WSlot slot = new WSlot(pool);
      allocate(slot);
      if (size == targetSize) {
        relieveTimeout = 10;
      }
    }
    WSlot slot;
    try {
      slot = pool.relieve(relieveTimeout, relieveUnit);
      if (slot != null) {
        deallocate(slot);
        allocate(slot);
      }
    } catch (InterruptedException e) {
      // take it as a shutdown signal
      pool.shutdown();
    }
  }

  private void allocate(WSlot slot) {
    if (!runnable) {
      return;
    }
    try {
      slot.obj = allocator.allocate(slot);
      if (slot.obj != null) {
        size++;
      } else {
        slot.poison = new NullPointerException("allocation returned null");
      }
    } catch (Exception e) {
      slot.poison = e;
    }
    slot.created = System.currentTimeMillis();
    pool.release(slot);
  }

  private void deallocate(WSlot slot) {
    try {
      if (slot.obj != null) {
        size--;
        allocator.deallocate((Poolable) slot.obj);
      }
    } catch (Exception e) {
      // ignored as per spec.
    }
    slot.created = 0;
    slot.next = null;
    slot.obj = null;
    slot.poison = null;
    slot.claimed = false;
  }

  private void deallocateAll() {
    while (size > 0) {
      WSlot slot;
      try {
        slot = pool.relieve(relieveTimeout, relieveUnit);
        if (slot == null) {
          continue;
        }
        deallocate(slot);
      } catch (InterruptedException e) {
        // ignore interrupts - we're already trying to shut down
      }
    }
  }
}
