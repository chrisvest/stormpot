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

import java.lang.ref.WeakReference;

final class PreciseLeakDetector {
  private static final int branchPower =
      Integer.getInteger("stormpot.PreciseLeakDetector.branchPower", 5);
  private static final int branchFactor = 1 << branchPower;
  private static final int branchMask = branchFactor - 1;

  private static class WeakRef extends WeakReference<Object> {
    WeakRef next;

    public WeakRef(Object referent) {
      super(referent);
    }
  }

  // Both guarded by synchronized(this)
  private Object[] probes;
  private int leakedObjectCount;

  PreciseLeakDetector() {
    // The probes are basically a two-level Bagwell hash trie with linked list
    // buckets at the end, to reduce the proliferation of arrays.
    // Basically, we compute the identity hash code for an object, and the
    // least significant 5 bits are the index into the first-level 32 element
    // 'probe' array. When inserting, if that slot is null, then we insert the
    // WeakRef directly into that place. In fact, we allow building a WeakRef
    // chain up to 4 elements long, directly in the first-level 'probes' array.
    // Otherwise we add the second-level 32 element array, rehash the existing
    // chain into it, by using the next 5 second-least significant bits as
    // index. From here on, we don't create any more levels, but just link the
    // WeakRefs together like a daisy-chain. This way, we create at most 33
    // arrays, with room for 1024 objects. Most pools probably won't need to
    // have more than that many objects registered at a time.
    probes = new Object[branchFactor];
  }

  synchronized void register(Object obj) {
    int hash1 = System.identityHashCode(obj) & branchMask;

    Object current = probes[hash1];

    if (current == null) {
      probes[hash1] = new WeakRef(obj);
      return;
    }

    if (current instanceof WeakRef) {
      WeakRef currentRef = (WeakRef) current;
      if (chainShorterThan(4, current)) {
        WeakRef ref = new WeakRef(obj);
        ref.next = currentRef;
        probes[hash1] = ref;
      } else {
        WeakRef[] level2 = new WeakRef[branchFactor];
        WeakRef ref = new WeakRef(obj);
        ref.next = currentRef;
        assocLevel2(level2, ref);
        probes[hash1] = level2;
      }
      return;
    }

    WeakRef[] level2 = (WeakRef[]) current;
    assocLevel2(level2, new WeakRef(obj));
  }

  private void assocLevel2(WeakRef[] level2, WeakRef chain) {
    WeakRef next;
    Object obj;
    while (chain != null) {
      next = chain.next;
      obj = chain.get();

      if (obj != null) {
        int hash2 = (System.identityHashCode(obj) >> branchPower) & branchMask;
        chain.next = level2[hash2];
        level2[hash2] = chain;
      } else {
        leakedObjectCount++;
      }

      chain = next;
    }
  }

  private boolean chainShorterThan(int length, Object weakRef) {
    WeakRef ref = (WeakRef) weakRef;

    while (ref != null) {
      if (length == 0) {
        return false;
      }
      length--;
      ref = ref.next;
    }
    return true;
  }

  synchronized void unregister(Object obj) {
    int hash0 = System.identityHashCode(obj);
    int hash1 = hash0 & branchMask;

    Object current = probes[hash1];

    if (current instanceof WeakRef) {
      WeakRef ref = (WeakRef) current;
      probes[hash1] = removeFromChain(ref, obj);
      return;
    }

    int hash2 = (hash0 >> branchPower) & branchMask;
    WeakRef[] level2 = (WeakRef[]) current;
    level2[hash2] = removeFromChain(level2[hash2], obj);
  }

  private WeakRef removeFromChain(WeakRef chain, Object obj) {
    WeakRef newChain = null;
    while (chain != null) {
      WeakRef next = chain.next;
      Object current = chain.get();
      if (current == null) {
        leakedObjectCount++;
      } else if (current == obj) {
        chain.clear();
      } else {
        chain.next = newChain;
        newChain = chain;
      }
      chain = next;
    }
    return newChain;
  }

  synchronized long countLeakedObjects() {
    for (int i = 0; i < probes.length; i++) {
      Object current = probes[i];
      if (current instanceof WeakRef) {
        probes[i] = pruneChain((WeakRef) current);
      } else if (current instanceof WeakRef[]) {
        WeakRef[] level2 = (WeakRef[]) current;
        for (int j = 0; j < level2.length; j++) {
          level2[j] = pruneChain(level2[j]);
        }
      }
    }

    return leakedObjectCount;
  }

  private WeakRef pruneChain(WeakRef chain) {
    WeakRef newChain = null;
    while (chain != null) {
      WeakRef next = chain.next;
      if (chain.get() == null) {
        leakedObjectCount++;
      } else {
        chain.next = newChain;
        newChain = chain;
      }
      chain = next;
    }
    return newChain;
  }
}
