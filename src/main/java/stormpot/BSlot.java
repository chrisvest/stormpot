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
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicIntegerFieldUpdater;

/**
 * This class is very sensitive to the memory layout, so be careful to measure
 * the effect of even the tiniest changes!
 * False-sharing is a fickle and vengeful mistress.
 */
final class BSlot<T extends Poolable>
    extends BSlotColdFields<T> {
  static final int CLAIMED = 1;
  static final int TLR_CLAIMED = 2;
  static final int LIVING = 3;
  static final int DEAD = 4;

  public BSlot(BlockingQueue<BSlot<T>> live, AtomicInteger poisonedSlots) {
    // Volatile write in the constructor: This object must be safely published,
    // so that we are sure that the volatile write happens-before other
    // threads observe the pointer to this object.
    super(DEAD, live, poisonedSlots);
  }
  
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
    String state;
    switch (slotState) {
      case DEAD: state = "DEAD"; break;
      case LIVING: state = "LIVING"; break;
      default: state = "STATE[" + slotState + "]";
    }
    return new PoolException("Slot release from bad state: " + state + ". " +
        "You most likely called release() twice on the same object.");
  }

  public void claim2live() {
    lazySet(LIVING);
  }

  public void claimTlr2live() {
    lazySet(LIVING);
  }

  public void dead2live() {
    lazySet(LIVING);
  }

  public void claim2dead() {
    lazySet(DEAD);
  }

  public boolean live2claim() {
    return compareAndSet(LIVING, CLAIMED);
  }
  
  public boolean live2claimTlr() {
    return compareAndSet(LIVING, TLR_CLAIMED);
  }
  
  public boolean live2dead() {
    return compareAndSet(LIVING, DEAD);
  }

  @Override
  public long getAgeMillis() {
    return System.currentTimeMillis() - created;
  }

  @Override
  public long getClaimCount() {
    return claims;
  }

  @Override
  public T getPoolable() {
    return obj;
  }

  public boolean isDead() {
    return get() == DEAD;
  }

  public boolean isLive() {
    return get() == LIVING;
  }

  public int getState() {
    return get();
  }

  public void incrementClaims() {
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
}


// stop checking line length
/*
The Java Object Layout rendition:

Running 64-bit HotSpot VM.
Using compressed references with 3-bit shift.
Objects are 8 bytes aligned.
Field sizes by type: 4, 1, 1, 2, 2, 4, 4, 8, 8 [bytes]
Array element sizes: 4, 1, 1, 2, 2, 4, 4, 8, 8 [bytes]

stormpot.BSlot object internals:
 OFFSET  SIZE          TYPE DESCRIPTION                    VALUE
      0     4               (object header)                01 21 88 61 (0000 0001 0010 0001 1000 1000 0110 0001)
      4     4               (object header)                6c 00 00 00 (0110 1100 0000 0000 0000 0000 0000 0000)
      8     4               (object header)                8a b6 61 df (1000 1010 1011 0110 0110 0001 1101 1111)
     12     4           int Padding1.p0                    0
     16     8          long Padding1.p1                    0
     24     8          long Padding1.p2                    0
     32     8          long Padding1.p3                    0
     40     8          long Padding1.p4                    0
     48     8          long Padding1.p5                    0
     56     8          long Padding1.p6                    0
     64     4           int PaddedAtomicInteger.state      4
     68     4               (alignment/padding gap)        N/A
     72     8          long Padding2.p1                    0
     80     8          long Padding2.p2                    0
     88     8          long Padding2.p3                    0
     96     8          long Padding2.p4                    0
    104     8          long Padding2.p5                    0
    112     8          long Padding2.p6                    0
    120     8          long Padding2.p7                    0
    128     8          long BSlotColdFields.stamp          0
    136     8          long BSlotColdFields.created        0
    144     8          long BSlotColdFields.claims         0
    152     4           int BSlotColdFields.x              1818331169
    156     4           int BSlotColdFields.y              938745813
    160     4           int BSlotColdFields.z              452465366
    164     4           int BSlotColdFields.w              1343246171
    168     4 BlockingQueue BSlotColdFields.live           null
    172     4      Poolable BSlotColdFields.obj            null
    176     4     Exception BSlotColdFields.poison         null
    180     4               (loss due to the next object alignment)
Instance size: 184 bytes (estimated, add this JAR via -javaagent: to get accurate result)
Space losses: 4 bytes internal + 4 bytes external = 8 bytes total
 */
// start checking line length
abstract class Padding1 {
  private int p0;
  private long p1, p2, p3, p4, p5, p6;
}

abstract class PaddedAtomicInteger extends Padding1 {
  private static final long stateFieldOffset;
  private static final AtomicIntegerFieldUpdater<PaddedAtomicInteger> updater;

  static {
    long theStateFieldOffset = 0;
    AtomicIntegerFieldUpdater<PaddedAtomicInteger> theStateUpdater = null;
    if (UnsafeUtil.hasUnsafe()) {
      theStateFieldOffset = UnsafeUtil.objectFieldOffset(PaddedAtomicInteger.class, "state");
    } else {
      theStateUpdater = AtomicIntegerFieldUpdater.newUpdater(PaddedAtomicInteger.class, "state");
    }
    stateFieldOffset = theStateFieldOffset;
    updater = theStateUpdater;
  }

  private volatile int state;

  public PaddedAtomicInteger(int state) {
    this.state = state;
  }

  protected final boolean compareAndSet(int expected, int update) {
    if (UnsafeUtil.hasUnsafe()) {
      return UnsafeUtil.compareAndSwapInt(this, stateFieldOffset, expected, update);
    } else {
      return updater.compareAndSet(this, expected, update);
    }
  }

  protected final void lazySet(int update) {
    if (UnsafeUtil.hasUnsafe()) {
      UnsafeUtil.putOrderedInt(this, stateFieldOffset, update);
    } else {
      updater.lazySet(this, update);
    }
  }

  protected int get() {
    return state;
  }
}

abstract class Padding2 extends PaddedAtomicInteger {
  private long p1, p2, p3, p4, p5, p6;

  public Padding2(int state) {
    super(state);
  }
}

abstract class BSlotColdFields<T extends Poolable> extends Padding2 implements Slot, SlotInfo<T> {
  final BlockingQueue<BSlot<T>> live;
  final AtomicInteger poisonedSlots;
  long stamp;
  long created;
  T obj;
  Exception poison;
  long claims;

  public BSlotColdFields(
      int state,
      BlockingQueue<BSlot<T>> live,
      AtomicInteger poisonedSlots) {
    super(state);
    this.live = live;
    this.poisonedSlots = poisonedSlots;
  }

  // XorShift PRNG with a 2^128-1 period.
  int x = System.identityHashCode(this);
  int y = -938745813;
  int z = 452465366;
  int w = 1343246171;

  @Override
  public int randomInt() {
    int t = x^(x<<15);
    //noinspection SuspiciousNameCombination
    x = y; y = z; z = w;
    return w = (w^(w>>>21))^(t^(t>>>4));
  }

  @Override
  public void expire(Poolable obj) {
    if (poison != BlazePool.EXPLICIT_EXPIRE_POISON) {
      poison = BlazePool.EXPLICIT_EXPIRE_POISON;
    }
  }
}
