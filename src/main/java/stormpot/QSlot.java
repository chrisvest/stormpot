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
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

final class QSlot<T extends Poolable> implements Slot, SlotInfo<T> {
  final BlockingQueue<QSlot<T>> live;
  final AtomicInteger poisonedSlots;
  final AtomicBoolean claimed;
  T obj;
  Exception poison;
  long created;
  long claims;
  long stamp;
  boolean expired;

  public QSlot(BlockingQueue<QSlot<T>> live, AtomicInteger poisonedSlots) {
    this.live = live;
    this.poisonedSlots = poisonedSlots;
    this.claimed = new AtomicBoolean(true);
  }
  
  public void claim() {
    claimed.set(true);
    claims++;
  }

  public void release(Poolable obj) {
    if (claimed.compareAndSet(true, false)) {
      if (expired) {
        poisonedSlots.getAndIncrement();
      }
      live.offer(this);
    }
  }

  @Override
  public void expire(Poolable obj) {
    expired = true;
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
  public long getStamp() {
    return stamp;
  }

  @Override
  public void setStamp(long stamp) {
    this.stamp = stamp;
  }
}
