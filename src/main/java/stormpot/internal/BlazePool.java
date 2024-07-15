/*
 * Copyright © 2011-2024 Chris Vest (mr.chrisvest@gmail.com)
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package stormpot.internal;

import stormpot.Completion;
import stormpot.Expiration;
import stormpot.ManagedPool;
import stormpot.MetricsRecorder;
import stormpot.Pool;
import stormpot.PoolBuilder;
import stormpot.PoolException;
import stormpot.PoolTap;
import stormpot.Poolable;
import stormpot.Timeout;

import java.util.Objects;
import java.util.concurrent.LinkedTransferQueue;
import java.util.concurrent.TimeUnit;

/**
 * BlazePool is a highly optimised {@link Pool} implementation that consists of
 * a queues of Poolable instances, the access to which is made faster with
 * clever use of ThreadLocals.
 * <p>
 * Object allocation always happens in a dedicated thread, off-loading the
 * cost of allocating the pooled objects. This leads to reduced deviation
 * in the times it takes claim method to complete, provided the pool is not
 * depleted.
 * <p>
 * BlazePool optimises for the case where the same threads need to claim and
 * release objects over and over again. On the other hand, if the releasing
 * thread tends to differ from the claiming thread, then the major optimisation
 * in BlazePool is defeated, and performance regresses to a slow-path that is
 * limited by contention on a blocking queue.
 *
 * @author Chris Vest
 * @param <T> The type of {@link Poolable} managed by this pool.
 */
public final class BlazePool<T extends Poolable> implements Pool<T>, ManagedPool {

  private static final Exception SHUTDOWN_POISON = new PoisonException("Stormpot Poison: Shutdown");
  static final Exception EXPLICIT_EXPIRE_POISON = new PoisonException("Stormpot Poison: Expired");

  private final LinkedTransferQueue<BSlot<T>> live;
  private final RefillPile<T> disregardPile;
  private final RefillPile<T> newAllocations;
  private final AllocationController<T> allocator;
  private final ThreadLocal<BSlotCache<T>> tlr;
  private final Expiration<? super T> deallocRule;
  private final MetricsRecorder metricsRecorder;

  /**
   * A special slot used to signal that the pool has been shut down.
   */
  private final BSlot<T> poisonPill;

  private volatile boolean shutdown;

  /**
   * Construct a new BlazePool instance based on the given {@link PoolBuilder}.
   * @param builder The pool configuration to use.
   */
  public BlazePool(PoolBuilderImpl<T> builder, AllocationProcess factory) {
    live = new LinkedTransferQueue<>();
    disregardPile = new RefillPile<>(live);
    newAllocations = new RefillPile<>(live);
    tlr = new ThreadLocalBSlotCache<>();
    poisonPill = new BSlot<>(live, null);
    poisonPill.poison = SHUTDOWN_POISON;
    deallocRule = builder.getExpiration();
    metricsRecorder = builder.getMetricsRecorder();
    allocator = factory.buildAllocationController(
        live, disregardPile, newAllocations, builder, poisonPill);
  }

  @Override
  public T claim(Timeout timeout)
      throws PoolException, InterruptedException {
    return claim(timeout, tlr.get());
  }

  @Override
  public T tryClaim() throws PoolException {
    return tryClaim(tlr.get());
  }

  T claim(Timeout timeout, BSlotCache<T> cache)
      throws PoolException, InterruptedException {
    Objects.requireNonNull(timeout, "Timeout cannot be null.");
    T obj = tlrClaim(cache);
    if (obj != null) return obj;
    // The thread-local claim failed, so we have to go through the slow-path.
    return slowClaim(timeout, cache);
  }

  T tryClaim(BSlotCache<T> cache) throws PoolException {
    T obj = tlrClaim(cache);
    if (obj != null) return obj;
    // The thread-local claim failed, so we have to go through the slow-path.
    return slowClaim(cache);
  }

  private T tlrClaim(BSlotCache<T> cache) {
    BSlot<T> slot = cache.slot;
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
      if (isValid(slot, cache, true)) {
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
    return null;
  }

  private T slowClaim(BSlotCache<T> cache) throws PoolException {
    BSlot<T> slot;
    slot = newAllocations.pop();
    for (;;) {
      if (slot == null) {
        slot = live.poll();
      }
      if (slot == null) {
        // No blocking when the queue is empty.
        disregardPile.refill();
        return null;
      } else if (slot.live2claim()) {
        if (isValid(slot, cache, false)) {
          slot.incrementClaims();
          cache.slot = slot;
          return slot.obj;
        }
      } else {
        disregardPile.push(slot);
      }
    }
  }

  private T slowClaim(Timeout timeout, BSlotCache<T> cache) throws PoolException, InterruptedException {
    // The slow-path for claim is in its own method to allow the fast-path to
    // inline separately. At this point, taking a performance hit is
    // inevitable anyway, so we're allowed a bit more leeway.
    BSlot<T> slot;
    long startNanos = NanoClock.nanoTime();
    long timeoutNanos = timeout.getTimeoutInBaseUnit();
    long timeoutLeft = timeoutNanos;
    TimeUnit baseUnit = timeout.getBaseUnit();
    long maxWaitQuantum = baseUnit.convert(100, TimeUnit.MILLISECONDS);
    do {
      slot = newAllocations.pop();
      if (slot == null) {
        long pollWait = Math.min(timeoutLeft, maxWaitQuantum);
        slot = live.poll(pollWait, baseUnit);
      }
      if (slot == null) {
        disregardPile.refill();
      } else if (slot.live2claim()) {
        if (isValid(slot, cache, false)) {
          slot.incrementClaims();
          cache.slot = slot;
          return slot.obj;
        }
      } else {
        disregardPile.push(slot);
      }

      timeoutLeft = NanoClock.timeoutLeft(startNanos, timeoutNanos);
    } while (timeoutLeft > 0);
    return null;
  }

  private boolean isValid(BSlot<T> slot, BSlotCache<T> cache, boolean isTlr) {
    return !isInvalid(slot, cache, isTlr);
  }

  private boolean isInvalid(BSlot<T> slot, BSlotCache<T> cache, boolean isTlr) {
    if (isUncommonlyInvalid(slot)) {
      return handleUncommonInvalidation(slot, cache, isTlr);
    }

    try {
      return deallocRule.hasExpired(slot)
          && handleCommonInvalidation(slot, cache, null);
    } catch (Throwable ex) {
      return handleCommonInvalidation(slot, cache, ex);
    }
  }

  private boolean isUncommonlyInvalid(BSlot<T> slot) {
    return shutdown | slot.poison != null;
  }

  private boolean handleUncommonInvalidation(
      BSlot<T> slot, BSlotCache<T> cache, boolean isTlr) {
    Exception poison = slot.poison;
    if (poison != null) {
      return dealWithSlotPoison(slot, cache, isTlr, poison);
    } else {
      kill(slot, cache);
      throw new IllegalStateException("Pool has been shut down");
    }
  }

  private boolean handleCommonInvalidation(
      BSlot<T> slot, BSlotCache<T> cache, Throwable exception) {
    kill(slot, cache);
    if (exception != null) {
      String msg = "Got exception when checking whether an object had expired";
      throw new PoolException(msg, exception);
    }
    return true;
  }

  private boolean dealWithSlotPoison(
      BSlot<T> slot, BSlotCache<T> cache, boolean isTlr, Exception poison) {
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
      kill(slot, cache);
      if (isTlr || poison == EXPLICIT_EXPIRE_POISON) {
        return true;
      } else {
        throw new PoolException("Allocation failed", poison);
      }
    }
  }

  private void kill(BSlot<T> slot, BSlotCache<T> cache) {
    // The use of claim2dead() here ensures that we don't put slots into the
    // dead-queue more than once. Many threads might have this as their
    // TLR-slot and try to tlr-claim it, but only when a slot has been normally
    // claimed, that is, pulled off the live-queue, can it be put into the
    // dead-queue. This helps ensure that a slot will only ever be in at most
    // one queue.
    if (slot.isClaimed()) {
      slot.claim2dead();
      allocator.offerDeadSlot(slot);
    } else {
      slot.claimTlr2live();
      cache.slot = null;
    }
  }

  @Override
  public Completion shutdown() {
    shutdown = true;
    return allocator.shutdown();
  }

  @Override
  public void setTargetSize(int size) {
    if (size < 0) {
      throw new IllegalArgumentException(
          "Target pool size must be positive");
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
  public ManagedPool getManagedPool() {
    return this;
  }

  @Override
  public PoolTap<T> getThreadLocalTap() {
    return new BlazePoolThreadLocalTap<>(this);
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
  
  @Override
  public int getCurrentAllocatedCount() {
    return allocator.allocatedSize();
  }
  
  @Override
  public int getCurrentInUseCount() {
    return allocator.inUse();
  }
}
