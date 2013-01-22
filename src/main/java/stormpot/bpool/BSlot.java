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

import java.util.concurrent.BlockingQueue;
import java.util.concurrent.atomic.AtomicInteger;

import stormpot.PoolException;
import stormpot.Poolable;
import stormpot.Slot;
import stormpot.SlotInfo;

class BSlot<T extends Poolable> implements Slot, SlotInfo<T> {
  static final int LIVING = 1;
  static final int CLAIMED = 2;
  static final int TLR_CLAIMED = 3;
  static final int DEAD = 4;
  
  final BlockingQueue<BSlot<T>> live;
  private final AtomicInteger state;
  T obj;
  Exception poison;
  long created;
  long claims;
  
  public BSlot(BlockingQueue<BSlot<T>> live) {
    this.live = live;
    this.state = new AtomicInteger(DEAD);
  }
  
  public void release(Poolable obj) {
    int slotState = 0;
    do {
      slotState = state.get();
      // We loop here because TLR_CLAIMED slots can be concurrently changed
      // into normal CLAIMED slots.
    } while (!releaseState(slotState));
    if (slotState == CLAIMED) {
      live.offer(this);
    }
  }
  
  private boolean releaseState(int slotState) {
    if (slotState == TLR_CLAIMED) {
      return claimTlr2live();
    } else if (slotState == CLAIMED) {
      return claim2live();
    }
    throw new PoolException("Slot release from bad state: " + slotState);
  }
  
  public boolean claim2live() {
    // why would this ever fail?
    return cas(CLAIMED, LIVING);
    
    // TODO maybe we can do this instead?
//    state.lazySet(LIVING);
//    return true;
  }
  
  public boolean claimTlr2live() {
    return cas(TLR_CLAIMED, LIVING);
  }
  
  public boolean live2claim() {
    return cas(LIVING, CLAIMED);
  }
  
  public boolean live2claimTlr() {
    return cas(LIVING, TLR_CLAIMED);
  }
  
  public boolean claimTlr2claim() {
    return cas(TLR_CLAIMED, CLAIMED);
  }
  
  public boolean claim2dead() {
    return cas(CLAIMED, DEAD);
  }
  
  public boolean dead2live() {
    return cas(DEAD, LIVING);
  }
  
  public boolean live2dead() {
    return cas(LIVING, DEAD);
  }

  private boolean cas(int expected, int update) {
    return state.compareAndSet(expected, update);
    
    // TODO see if this is a performance boon or not:
//    if (state.get() == expected) {
//      return state.compareAndSet(expected, update);
//    }
//    return false;
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
    return state.get() == DEAD;
  }
  
  public int getState() {
    return state.get();
  }

  public void incrementClaims() {
    claims++;
  }
}
