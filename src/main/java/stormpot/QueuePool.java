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
import java.util.concurrent.LinkedTransferQueue;
import java.util.concurrent.ThreadFactory;

/**
 * QueuePool is a fairly simple {@link Pool} implementation that basically
 * consists of a queue of Poolable instances, and a Thread to allocate them.
 *
 * This means that the object allocation always happens in a dedicated thread.
 * This means that no thread that calls any of the claim methods, will incur
 * the overhead of allocating Poolables. This should lead to reduced deviation
 * in the times it takes claim method to complete, provided the pool is not
 * depleted.
 *
 * The design is simple and straight forward, and exhibits a reasonable
 * base-line performance in all cases. If, however, the same threads are going
 * to claim and release objects from the pool over and over again — for
 * instance in the case of a typical Java web application — then
 * {@link stormpot.BlazePool} is likely going to yield better
 * performance.
 *
 * @author Chris Vest <mr.chrisvest@gmail.com>
 * @param <T> The type of {@link Poolable} managed by this pool.
 */
public final class QueuePool<T extends Poolable>
    extends Pool<T> implements ManagedPool {
  private final BlockingQueue<QSlot<T>> live;
  private final BlockingQueue<QSlot<T>> dead;
  private final QAllocThread<T> allocator;
  private final Thread allocatorThread;
  private final Expiration<? super T> expiration;
  private final MetricsRecorder metricsRecorder;
  private volatile boolean shutdown = false;

  /**
   * Special slot used to signal that the pool has been shut down.
   */
  final QSlot<T> poisonPill = new QSlot<>(null, null);

  /**
   * Construct a new QueuePool instance based on the given {@link Config}.
   * @param config The pool configuration to use.
   */
  public QueuePool(Config<T> config) {
    live = new LinkedTransferQueue<>();
    dead = new LinkedTransferQueue<>();
    synchronized (config) {
      config.validate();
      ThreadFactory factory = config.getThreadFactory();
      metricsRecorder = config.getMetricsRecorder();
      expiration = config.getExpiration();
      allocator = new QAllocThread<>(
          live, dead, config, poisonPill);
      allocatorThread = factory.newThread(allocator);
    }
    allocatorThread.start();
  }

  private void checkForPoison(QSlot<T> slot) {
    if (slot == poisonPill) {
      live.offer(poisonPill);
      throw new IllegalStateException("Pool has been shut down");
    }
    if (slot.poison != null) {
      Exception poison = slot.poison;
      dead.offer(slot);
      throw new PoolException("Allocation failed", poison);
    }
    if (shutdown) {
      dead.offer(slot);
      throw new IllegalStateException("Pool has been shut down");
    }
  }

  private boolean isInvalid(QSlot<T> slot) {
    boolean invalid = true;
    RuntimeException exception = null;
    boolean expired = slot.expired;
    try {
      invalid = expiration.hasExpired(slot);
    } catch (Throwable ex) {
      exception = new PoolException(
          "Got exception when checking whether an object had expired", ex);
    }
    invalid |= expired;
    if (invalid) {
      // it's invalid - into the dead queue with it and continue looping
      dead.offer(slot);
      if (exception != null) {
        throw exception;
      }
    } else {
      // it's valid - claim it and stop looping
      slot.claim();
    }
    return invalid;
  }

  @Override
  public T claim(Timeout timeout) throws PoolException,
      InterruptedException {
    if (timeout == null) {
      throw new IllegalArgumentException("Timeout cannot be null");
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

  @Override
  public Completion shutdown() {
    shutdown = true;
    return allocator.shutdown(allocatorThread);
  }

  @Override
  public void setTargetSize(int size) {
    if (size < 1) {
      throw new IllegalArgumentException("Target pool size must be at least 1");
    }
    if (shutdown) {
      return;
    }
    allocator.setTargetSize(size);
  }

  @Override
  public int getTargetSize() {
    return allocator.getTargetSize();
  }

  @Override
  public long getAllocationCount() {
    return allocator.getAllocationCount();
  }

  @Override
  public long getFailedAllocationCount() {
    return allocator.getFailedAllocationCount();
  }

  @Override
  public boolean isShutDown() {
    return shutdown;
  }

  @Override
  public double getObjectLifetimePercentile(double percentile) {
    if (metricsRecorder == null) {
      return Double.NaN;
    }
    return metricsRecorder.getObjectLifetimePercentile(percentile);
  }

  @Override
  public double getAllocationLatencyPercentile(double percentile) {
    if (metricsRecorder == null) {
      return Double.NaN;
    }
    return metricsRecorder.getAllocationLatencyPercentile(percentile);
  }

  @Override
  public double getAllocationFailureLatencyPercentile(double percentile) {
    if (metricsRecorder == null) {
      return Double.NaN;
    }
    return metricsRecorder.getAllocationFailureLatencyPercentile(percentile);
  }

  @Override
  public double getReallocationLatencyPercentile(double percentile) {
    if (metricsRecorder == null) {
      return Double.NaN;
    }
    return metricsRecorder.getReallocationLatencyPercentile(percentile);
  }

  @Override
  public double getReallocationFailureLatencyPercentile(double percentile) {
    if (metricsRecorder == null) {
      return Double.NaN;
    }
    return metricsRecorder.getReallocationFailurePercentile(percentile);
  }

  @Override
  public double getDeallocationLatencyPercentile(double percentile) {
    if (metricsRecorder == null) {
      return Double.NaN;
    }
    return metricsRecorder.getDeallocationLatencyPercentile(percentile);
  }

  @Override
  public long getLeakedObjectsCount() {
    return allocator.countLeakedObjects();
  }
}
