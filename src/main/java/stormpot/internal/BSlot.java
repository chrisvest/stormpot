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

import stormpot.Allocator;
import stormpot.PoolException;
import stormpot.Poolable;
import stormpot.Slot;
import stormpot.SlotInfo;

import java.lang.invoke.MethodHandles;
import java.lang.invoke.VarHandle;
import java.lang.ref.Reference;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

/**
 * The BSlot implements the {@link Slot} and {@link SlotInfo} interfaces for the blaze pool implementation.
 *
 * @param <T> The concrete poolable type.
 */
public sealed class BSlot<T extends Poolable> implements Slot, SlotInfo<T>, Task permits BSlotPadded {
  private static final int CLAIMED = 1;
  private static final int TLR_CLAIMED = 2;
  private static final int LIVING = 3;
  private static final int DEAD = 4;

  private static final VarHandle STATE;
  static {
    try {
      MethodHandles.Lookup lookup = MethodHandles.lookup();
      STATE = lookup.findVarHandle(BSlot.class, "state", int.class)
              .withInvokeExactBehavior();
    } catch (NoSuchFieldException | IllegalAccessException e) {
      throw new AssertionError("Failed to initialise the state VarHandle.", e);
    }
  }

  @SuppressWarnings("FieldMayBeFinal")
  private volatile int state;

  final BlockingQueue<BSlot<T>> live;
  final AtomicLong poisonedSlots;
  long stamp;
  long createdNanos;
  /**
   * The object being pooled.
   */
  public T obj;
  /**
   * Any exception encountered when trying to allocate an object for this slot.
   */
  public Exception poison;
  /**
   * A reference to the allocated object, used by the {@link PreciseLeakDetector}.
   */
  public Reference<Object> leakCheck; // Used by PreciseLeakDetector
  /**
   * Used to make sure objects are deallocated by the same allocator that created them,
   * for pools that support switching allocators at runtime.
   */
  Allocator<T> allocator;

  /**
   * Create a new BSlot instance, which will return to the given live queue when released from a claim,
   * and update the given counter when poisoned.
   * @param live The queue of live slots.
   * @param poisonedSlots The counter of poisoned slots.
   */
  public BSlot(BlockingQueue<BSlot<T>> live, AtomicLong poisonedSlots) {
    // Volatile write in the constructor: This object must be safely published,
    // so that we are sure that the volatile write happens-before other
    // threads observe the pointer to this object.
    state = DEAD;
    this.live = live;
    this.poisonedSlots = poisonedSlots;
  }

  @SuppressWarnings("SameParameterValue")
  final boolean compareAndSet(int expected, int update) {
    return STATE.compareAndSet(this, expected, update);
  }

  final void lazySet(int update) {
    STATE.setOpaque(this, update);
  }

  int get() {
    return state;
  }

  @Override
  public void expire(Poolable obj) {
    if (poison != BlazePool.EXPLICIT_EXPIRE_POISON) {
      poison = BlazePool.EXPLICIT_EXPIRE_POISON;
    }
  }

  @Override
  public void release(Poolable obj) {
    if (poison == BlazePool.EXPLICIT_EXPIRE_POISON) {
      poisonedSlots.getAndIncrement();
    }
    int slotState = getClaimState();
    lazySet(LIVING);
    if (slotState == CLAIMED) {
      live.offer(this);
    }
  }

  private int getClaimState() {
    int slotState = get();
    if (slotState > TLR_CLAIMED) {
      throw badStateOnTransitionToLive(slotState);
    }
    return slotState;
  }

  private PoolException badStateOnTransitionToLive(int slotState) {
    String state = switch (slotState) {
      case DEAD -> "DEAD";
      case LIVING -> "LIVING";
      default -> "STATE[" + slotState + "]";
    };
    return new PoolException("Slot release from bad state: " + state + ". " +
            "You most likely called release() twice on the same object.");
  }

  void claim2live() {
    lazySet(LIVING);
  }

  /**
   * Transition this slot from thread-locally claimed, to live.
   */
  public void claimTlr2live() {
    lazySet(LIVING);
  }

  /**
   * Transition this slot from dead to live.
   */
  public void dead2live() {
    lazySet(LIVING);
  }

  /**
   * Transition this slot from claimed to dead.
   */
  public void claim2dead() {
    lazySet(DEAD);
  }

  /**
   * Attempt to transition this slot from live to claimed.
   * @return {@code true} if the state transition succeeds, otherwise {@code false}.
   */
  public boolean live2claim() {
    return compareAndSet(LIVING, CLAIMED);
  }

  /**
   * Attempt to transition this slot from live to thread-locally claimed.
   * @return {@code true} if the state transition succeeds, otherwise {@code false}.
   */
  public boolean live2claimTlr() {
    return compareAndSet(LIVING, TLR_CLAIMED);
  }
  
  boolean live2dead() {
    return compareAndSet(LIVING, DEAD);
  }

  @Override
  public long getAgeMillis() {
    return TimeUnit.NANOSECONDS.toMillis(NanoClock.elapsed(createdNanos));
  }

  @Override
  public long getCreatedNanoTime() {
    return createdNanos;
  }

  @Override
  public T getPoolable() {
    return obj;
  }

  boolean isDead() {
    return get() == DEAD;
  }

  boolean isLive() {
    return get() == LIVING;
  }

  boolean isClaimed() {
    return get() == CLAIMED;
  }
  
  boolean isClaimedOrThreadLocal() {
    int state = get();
    return state == CLAIMED || state == TLR_CLAIMED;
  }

  @Override
  public long getStamp() {
    return stamp;
  }

  @Override
  public void setStamp(long stamp) {
    this.stamp = stamp;
  }

  @Override
  public String toString() {
    int state = get();
    String s;
    if (state == CLAIMED) {
      s = "CLAIMED";
    } else if (state == TLR_CLAIMED) {
      s = "TLR_CLAIMED";
    } else if (state == LIVING) {
      s = "LIVING";
    } else if (state == DEAD) {
      s = "DEAD";
    } else {
      s = "UnknownState(" + state + ")";
    }
    return "BSolt[" + s + ", obj = " + obj + ", poison = " + poison + "]";
  }
}
