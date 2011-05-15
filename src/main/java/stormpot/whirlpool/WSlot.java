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
package stormpot.whirlpool;

import stormpot.Poolable;
import stormpot.Slot;

class WSlot implements Slot {
  final Whirlpool pool;
  final String name;
  long created;
  Poolable obj;
  WSlot next;
  Exception poison;
  boolean claimed;
  
  public WSlot(Whirlpool pool, String name) {
    this.pool = pool;
    this.name = name;
  }

  public void release(Poolable obj) {
    if (obj != this.obj || !claimed) {
      throw new IllegalStateException("illegal release");
    }
    claimed = false;
    pool.release(this);
  }
  
  @Override
  public String toString() {
    return "WSlot[" + name +": " + obj + "]@" + Integer.toHexString(hashCode());
  }
}