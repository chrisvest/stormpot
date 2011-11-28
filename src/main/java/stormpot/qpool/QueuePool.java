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
import java.util.concurrent.TimeUnit;

import stormpot.Completion;
import stormpot.Config;
import stormpot.LifecycledPool;
import stormpot.PoolException;
import stormpot.Poolable;

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
@SuppressWarnings("unchecked")
public final class QueuePool<T extends Poolable> implements LifecycledPool<T> {
  static final QSlot KILL_PILL = new QSlot(null);
  private final BlockingQueue<QSlot<T>> live;
  private final BlockingQueue<QSlot<T>> dead;
  private final QAllocThread allocThread;
  private volatile boolean shutdown = false;
  
  /**
   * Construct a new QueuePool instance based on the given {@link Config}.
   * @param config The pool configuration to use.
   */
  public QueuePool(Config config) {
    live = new LinkedBlockingQueue<QSlot<T>>();
    dead = new LinkedBlockingQueue<QSlot<T>>();
    synchronized (config) {
      config.validate();
      allocThread = new QAllocThread(live, dead, config);
    }
    allocThread.start();
  }

  public T claim() throws PoolException, InterruptedException {
    QSlot<T> slot;
    do {
      slot = live.take();
    } while (invalid(slot));
    return slot.obj;
  }

  private boolean invalid(QSlot<T> slot) {
    if (slot == null) {
      return false;
    }
    if (slot == KILL_PILL) {
      live.offer(KILL_PILL);
      throw new IllegalStateException("pool is shut down");
    }
    if (slot.poison != null) {
      Exception poison = slot.poison;
      dead.offer(slot);
      throw new PoolException("allocation failed", poison);
    }
    if (slot.expired()) {
      dead.offer(slot);
      return true;
    }
    if (shutdown) {
      dead.offer(slot);
      throw new IllegalStateException("pool is shut down");
    }
    slot.claim();
    return false;
  }

  public T claim(long timeout, TimeUnit unit) throws PoolException,
      InterruptedException {
    if (unit == null) {
      throw new IllegalArgumentException("timeout TimeUnit cannot be null.");
    }
    QSlot<T> slot;
    long deadline = System.currentTimeMillis() + unit.toMillis(timeout);
    do {
      long timeoutLeft = deadline - System.currentTimeMillis();
      slot = live.poll(timeoutLeft, TimeUnit.MILLISECONDS);
    } while (invalid(slot));
    return slot == null? null : slot.obj;
  }

  public Completion shutdown() {
    shutdown = true;
    allocThread.interrupt();
    return new QPoolShutdownCompletion(allocThread);
  }
}
