/*
 * Copyright 2012 Chris Vest
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
package stormpot.bpool;

import static stormpot.bpool.BSlotState.*;

import java.util.concurrent.BlockingQueue;
import java.util.concurrent.atomic.AtomicReference;

import stormpot.PoolException;
import stormpot.Poolable;
import stormpot.Slot;
import stormpot.SlotInfo;

class BSlot<T extends Poolable> implements Slot, SlotInfo<T> {
  final BlockingQueue<BSlot<T>> live;
  private final AtomicReference<BSlotState> state;
  T obj;
  Exception poison;
  long created;
  long claims;
  Thread owner;
  
  public BSlot(BlockingQueue<BSlot<T>> live) {
    this.live = live;
    this.state = new AtomicReference<BSlotState>(dead);
  }
  
  public void release(Poolable obj) {
    BSlotState qSlotState = null;
    do {
      qSlotState = state.get();
      Thread claimer = owner;
      Thread releaser = Thread.currentThread();
      if (claimer != releaser) {
        throw new PoolException(
            "Expected release from claimer " + claimer + " but was " + releaser);
      }
      if (qSlotState != tlrClaimed && qSlotState != claimed) {
        throw new PoolException("Slot release from bad state: " + qSlotState);
      }
    } while (!(qSlotState == claimed? claim2live() : claimTlr2live()));
    if (qSlotState == claimed) {
      live.offer(this);
    }
  }
  
  public boolean claim2live() {
    return cas(claimed, living);
  }
  
  public boolean claimTlr2live() {
    return cas(tlrClaimed, living);
  }
  
  public boolean live2claim() {
    boolean cas = cas(living, claimed);
    if (cas) owner = Thread.currentThread();
    return cas;
  }
  
  public boolean live2claimTlr() {
    boolean cas = cas(living, tlrClaimed);
    if (cas) owner = Thread.currentThread();
    return cas;
  }
  
  public boolean claimTlr2claim() {
    return cas(tlrClaimed, claimed);
  }
  
  public boolean claim2dead() {
    return cas(claimed, dead);
  }
  
  public boolean dead2live() {
    return cas(dead, living);
  }
  
  public boolean live2dead() {
    return cas(living, dead);
  }

  private boolean cas(BSlotState expected, BSlotState update) {
    return state.compareAndSet(expected, update);
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
    return state.get() == dead;
  }
  
  public BSlotState getState() {
    return state.get();
  }

  public void incrementClaims() {
    claims++;
  }

  @Override
  public String toString() {
    return "[" + super.toString() + " " + state.get() + "]";
  }
}
