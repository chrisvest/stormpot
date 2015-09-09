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
import java.util.concurrent.TimeUnit;

/**
 * BlazePool is a highly optimised {@link Pool} implementation that consists of
 * a queues of Poolable instances, the access to which is made faster with
 * clever use of ThreadLocals.
 *
 * Object allocation always happens in a dedicated thread, off-loading the
 * cost of allocating the pooled objects. This leads to reduced deviation
 * in the times it takes claim method to complete, provided the pool is not
 * depleted.
 *
 * BlazePool optimises for the case where the same threads need to claim and
 * release objects over and over again. On the other hand, if the releasing
 * thread tends to differ from the claiming thread, then the major optimisation
 * in BlazePool is defeated, and performance regresses to a slow-path that is
 * a tad slower than {@link stormpot.QueuePool}.
 *
 * @author Chris Vest <mr.chrisvest@gmail.com>
 * @param <T> The type of {@link Poolable} managed by this pool.
 */
public final class BlazePool<T extends Poolable>
    extends Pool<T> implements ManagedPool {

  @SuppressWarnings("ThrowableInstanceNeverThrown")
  private static final Exception SHUTDOWN_POISON = new Exception();
  @SuppressWarnings("ThrowableInstanceNeverThrown")
  static final Exception EXPLICIT_EXPIRE_POISON = new Exception();

  private final BlockingQueue<BSlot<T>> live;
  private final DisregardBPile<T> disregardPile;
  private final BAllocThread<T> allocator;
  private final ThreadLocal<BSlot<T>> tlr;
  private final Thread allocatorThread;
  private final Expiration<? super T> deallocRule;
  private final MetricsRecorder metricsRecorder;

  /**
   * Special slot used to signal that the pool has been shut down.
   */
  private final BSlot<T> poisonPill;

  private volatile boolean shutdown;

  /**
   * Construct a new BlazePool instance based on the given {@link Config}.
   * @param config The pool configuration to use.
   */
  public BlazePool(Config<T> config) {
    live = new LinkedTransferQueue<>();
    disregardPile = new DisregardBPile<>(live);
    tlr = new ThreadLocal<>();
    poisonPill = new BSlot<>(live, null);
    poisonPill.poison = SHUTDOWN_POISON;

    synchronized (config) {
      config.validate();
      ThreadFactory factory = config.getThreadFactory();
      allocator = new BAllocThread<>(live, disregardPile, config, poisonPill);
      allocatorThread = factory.newThread(allocator);
      deallocRule = config.getExpiration();
      metricsRecorder = config.getMetricsRecorder();
    }
    allocatorThread.start();
  }

  @Override
  public T claim(Timeout timeout)
      throws PoolException, InterruptedException {
    assertTimeoutNotNull(timeout);
    BSlot<T> slot = tlr.get();
    // Note that the TLR slot at this point might have been tried by another
    // thread, found to be expired, put on the dead-queue and deallocated.
    // We handle this because slots always transition to the dead state before
    // they are put on the dead-queue, and if they are dead, then the
    // slot.live2claimTlr() call will fail.
    // Then we will eventually find another slot from the live-queue that we
    // can claim and make our new TLR slot.
    if (slot != null && slot.live2claimTlr()) {
      // Attempt the claim before checking the validity, because we might
      // already have claimed it.
      // If we checked validity before claiming, then we might find that it
      // had expired, and throw it in the dead queue, causing a claimed
      // Poolable to be deallocated before it is released.
      if (!isInvalid(slot, true)) {
        slot.incrementClaims();
        return slot.obj;
      }
      // We managed to tlr-claim the slot, but it turned out to be no good.
      // That means we now have to transition it from tlr-claimed to dead.
      // However, since we didn't pull it off of the live-queue, it might still
      // be in the live-queue. And since it might be in the live-queue, it
      // can't be put on the dead-queue. And since it can't be put on the
      // dead-queue, it also cannot transition to the dead state.
      // This effectively means that we have to transition it back to the live
      // state, and then let some pull it off of the live-queue, check it
      // again, and only then put it on the dead-queue.
      // It's cumbersome, but we have to do it this way, in order to prevent
      // duplicate entries in the queues. Otherwise we'd have a nasty memory
      // leak on our hands.
    }
    // The thread-local claim failed, so we have to go through the slow-path.
    return slowClaim(timeout);
  }

  private void assertTimeoutNotNull(Timeout timeout) {
    if (timeout == null) {
      throw new IllegalArgumentException("Timeout cannot be null");
    }
  }

  private T slowClaim(Timeout timeout)
      throws PoolException, InterruptedException {
    // The slow-path for claim is in its own method to allow the fast-path to
    // inline separately. At this point, taking a performance hit is
    // inevitable anyway, so we're allowed a bit more leeway.
    BSlot<T> slot;
    long deadline = timeout.getDeadline();
    long timeoutLeft = timeout.getTimeoutInBaseUnit();
    TimeUnit baseUnit = timeout.getBaseUnit();
    long maxWaitQuantum = baseUnit.convert(10, TimeUnit.MILLISECONDS);
    for (;;) {
      slot = live.poll(Math.min(timeoutLeft, maxWaitQuantum), baseUnit);
      if (slot == null) {
        if (timeoutLeft <= 0) {
          // we timed out while taking from the queue - just return null
          return null;
        } else {
          timeoutLeft = timeout.getTimeLeft(deadline);
          disregardPile.refillQueue();
          continue;
        }
      }

      if (slot.live2claim()) {
        if (isInvalid(slot, false)) {
          timeoutLeft = timeout.getTimeLeft(deadline);
        } else {
          break;
        }
      } else {
        disregardPile.addSlot(slot);
      }
    }
    slot.incrementClaims();
    tlr.set(slot);
    return slot.obj;
  }

  private boolean isInvalid(BSlot<T> slot, boolean isTlr) {
    if (isUncommonlyInvalid(slot)) {
      return handleUncommonInvalidation(slot, isTlr);
    }

    try {
      return deallocRule.hasExpired(slot)
          && handleCommonInvalidation(slot, null);
    } catch (Throwable ex) {
      return handleCommonInvalidation(slot, ex);
    }
  }

  private boolean isUncommonlyInvalid(BSlot<T> slot) {
    return shutdown | slot.poison != null;
  }

  private boolean handleUncommonInvalidation(BSlot<T> slot, boolean isTlr) {
    Exception poison = slot.poison;
    if (poison != null) {
      return dealWithSlotPoison(slot, isTlr, poison);
    } else {
      kill(slot);
      throw new IllegalStateException("Pool has been shut down");
    }
  }

  private boolean handleCommonInvalidation(BSlot<T> slot, Throwable exception) {
    kill(slot);
    if (exception != null) {
      String msg = "Got exception when checking whether an object had expired";
      throw new PoolException(msg, exception);
    }
    return true;
  }

  private boolean dealWithSlotPoison(BSlot<T> slot, boolean isTlr, Exception poison) {
    if (poison == SHUTDOWN_POISON) {
      // The poison pill means the pool has been shut down. The pill was
      // transitioned from live to claimed just prior to this check, so we
      // must transition it back to live and put it back into the live-queue
      // before throwing our exception.
      // Because we always throw when we see it, it will never become a
      // tlr-slot, and so we don't need to worry about transitioning from
      // tlr-claimed to live.
      slot.claim2live();
      live.offer(poisonPill);
      throw new IllegalStateException("Pool has been shut down");
    } else {
      kill(slot);
      if (isTlr || poison == EXPLICIT_EXPIRE_POISON) {
        return true;
      } else {
        throw new PoolException("Allocation failed", poison);
      }
    }
  }

  private void kill(BSlot<T> slot) {
    // The use of claim2dead() here ensures that we don't put slots into the
    // dead-queue more than once. Many threads might have this as their
    // TLR-slot and try to tlr-claim it, but only when a slot has been normally
    // claimed, that is, pulled off the live-queue, can it be put into the
    // dead-queue. This helps ensure that a slot will only ever be in at most
    // one queue.

    if (slot.getState() == BSlot.CLAIMED) {
      slot.claim2dead();
      allocator.offerDeadSlot(slot);
    } else {
      slot.claimTlr2live();
      tlr.set(null);
    }
  }

  @Override
  public Completion shutdown() {
    shutdown = true;
    return allocator.shutdown(allocatorThread);
  }

  @Override
  public void setTargetSize(int size) {
    if (size < 1) {
      throw new IllegalArgumentException(
          "Target pool size must be at least 1");
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
