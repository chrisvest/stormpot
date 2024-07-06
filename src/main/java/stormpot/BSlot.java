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
package stormpot;

import java.lang.invoke.MethodHandles;
import java.lang.invoke.VarHandle;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * This class is very sensitive to the memory layout, so be careful to measure
 * the effect of even the tiniest changes!
 * False-sharing can be quite sneaky.
 */
final class BSlot<T extends Poolable>
    extends BSlotColdFields<T> {
  private static final int CLAIMED = 1;
  private static final int TLR_CLAIMED = 2;
  private static final int LIVING = 3;
  private static final int DEAD = 4;

  BSlot(BlockingQueue<BSlot<T>> live, AtomicInteger poisonedSlots) {
    // Volatile write in the constructor: This object must be safely published,
    // so that we are sure that the volatile write happens-before other
    // threads observe the pointer to this object.
    super(DEAD, live, poisonedSlots);
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

  void claimTlr2live() {
    lazySet(LIVING);
  }

  void dead2live() {
    lazySet(LIVING);
  }

  void claim2dead() {
    lazySet(DEAD);
  }

  boolean live2claim() {
    return compareAndSet(LIVING, CLAIMED);
  }
  
  boolean live2claimTlr() {
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
  public long getClaimCount() {
    return claims;
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

  void incrementClaims() {
    claims++;
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

@SuppressWarnings("unused")
abstract class Padding1 {
  private byte p0;
  private byte p1;
  private byte p2;
  private byte p3;
}

abstract class PaddedAtomicInteger extends Padding1 {
  private static final VarHandle STATE;

  static {
    try {
      MethodHandles.Lookup lookup = MethodHandles.lookup();
      STATE = lookup.findVarHandle(PaddedAtomicInteger.class, "state", int.class);
    } catch (NoSuchFieldException | IllegalAccessException e) {
      throw new AssertionError("Failed to initialise the state VarHandle.", e);
    }
  }

  @SuppressWarnings("FieldMayBeFinal")
  private volatile int state;

  PaddedAtomicInteger(int state) {
    this.state = state;
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
}

abstract class Padding2 extends PaddedAtomicInteger {
  Padding2(int state) {
    super(state);
  }
}

abstract class BSlotColdFields<T extends Poolable> extends Padding2 implements Slot, SlotInfo<T> {
  final BlockingQueue<BSlot<T>> live;
  final AtomicInteger poisonedSlots;
  long stamp;
  long createdNanos;
  T obj;
  Exception poison;
  long claims;

  BSlotColdFields(
      int state,
      BlockingQueue<BSlot<T>> live,
      AtomicInteger poisonedSlots) {
    super(state);
    this.live = live;
    this.poisonedSlots = poisonedSlots;
  }

  @Override
  public void expire(Poolable obj) {
    if (poison != BlazePool.EXPLICIT_EXPIRE_POISON) {
      poison = BlazePool.EXPLICIT_EXPIRE_POISON;
    }
  }
}
