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
package stormpot.qpool;

import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;

import stormpot.Completion;
import stormpot.Config;
import stormpot.DeallocationRule;
import stormpot.LifecycledPool;
import stormpot.PoolException;
import stormpot.Poolable;
import stormpot.ResizablePool;
import stormpot.Timeout;

/**
 * QueuePool is a fairly simple {@link LifecycledPool} implementation that
 * basically consists of a queue of Poolable instances, and a Thread to
 * allocate them.
 * <p>
 * This means that the object allocation always happens in a dedicated thread.
 * This means that no thread that calls any of the claim methods, will incur
 * the overhead of allocating Poolables. This should lead to reduced deviation
 * in the times it takes claim method to complete, provided the pool is not
 * depleted.
 * @author Chris Vest &lt;mr.chrisvest@gmail.com&gt;
 * @param <T> The type of {@link Poolable} managed by this pool.
 */
public final class QueuePool<T extends Poolable>
implements LifecycledPool<T>, ResizablePool<T> {
  private final BlockingQueue<QSlot<T>> live;
  private final BlockingQueue<QSlot<T>> dead;
  private final QAllocThread<T> allocThread;
  private final DeallocationRule<? super T> deallocRule;
  private volatile boolean shutdown = false;
  
  /**
   * Construct a new QueuePool instance based on the given {@link Config}.
   * @param config The pool configuration to use.
   */
  public QueuePool(Config<T> config) {
    live = new LinkedBlockingQueue<QSlot<T>>();
    dead = new LinkedBlockingQueue<QSlot<T>>();
    synchronized (config) {
      config.validate();
      allocThread = new QAllocThread<T>(live, dead, config);
      deallocRule = config.getDeallocationRule();
    }
    allocThread.start();
  }

  private void checkForPoison(QSlot<T> slot) {
    if (slot == allocThread.POISON_PILL) {
      live.offer(allocThread.POISON_PILL);
      throw new IllegalStateException("pool is shut down");
    }
    if (slot.poison != null) {
      Exception poison = slot.poison;
      dead.offer(slot);
      throw new PoolException("allocation failed", poison);
    }
    if (shutdown) {
      dead.offer(slot);
      throw new IllegalStateException("pool is shut down");
    }
  }

  private boolean isInvalid(QSlot<T> slot) {
    boolean invalid = true;
    try {
      invalid = deallocRule.isInvalid(slot);
    } finally {
      if (invalid) {
        // it's invalid - into the dead queue with it and continue looping
        dead.offer(slot);
      } else {
        // it's valid - claim it and stop looping
        slot.claim();
      }
    }
    return invalid;
  }

  public T claim(Timeout timeout) throws PoolException,
      InterruptedException {
    if (timeout == null) {
      throw new IllegalArgumentException("timeout cannot be null");
    }
    QSlot<T> slot;
    long deadline = timeout.getDeadline();
    do {
      long timeoutLeft = timeout.getTimeLeft(deadline);
      slot = live.poll(timeoutLeft, timeout.getBaseUnit());
      if (slot == null) {
        // we timed out while taking from the queue - just return null
        return null;
      }
      checkForPoison(slot);
    } while (isInvalid(slot));
    return slot.obj;
  }

  public Completion shutdown() {
    shutdown = true;
    allocThread.interrupt();
    return new QPoolShutdownCompletion(allocThread);
  }

  public void setTargetSize(int size) {
    if (size < 1) {
      throw new IllegalArgumentException("target size must be at least 1");
    }
    allocThread.setTargetSize(size);
  }

  public int getTargetSize() {
    return allocThread.getTargetSize();
  }
}
