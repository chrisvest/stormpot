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

import java.util.Arrays;
import java.util.Iterator;
import java.util.Objects;
import java.util.stream.Stream;

/**
 * Like {@link java.util.IdentityHashMap}, but as a Set with no overhead for storing values.
 */
public class IdentityHashSet implements Iterable<Object> {
  // Implementation is based on open-addressing 2-choice hashing,
  // with 8-element buckets, and an 8-element stash for unresolvable collisions.

  private static final int BLOCK_LEN = 8;
  private final Object[] stash;
  private int stashSize;
  private int sectionLength;
  private int sectionBlocks;
  private Object[] table;

  public IdentityHashSet() {
    stash = new Object[BLOCK_LEN];
    sectionBlocks = 8;
    sectionLength = BLOCK_LEN * sectionBlocks;
    table = new Object[sectionLength * 2];
  }

  public void add(Object obj) {
    for (;;) {
      int mask = sectionBlocks - 1;
      int ihash = System.identityHashCode(obj);
      int h1 = BLOCK_LEN * (ihash & mask);
      int h2 = sectionLength + (BLOCK_LEN * (fmix32(ihash) & mask));
      for (int i = 0; i < stashSize; i++) {
        if (obj == stash[i]) {
          return;
        }
      }
      int e1 = Integer.MAX_VALUE;
      int e2 = Integer.MAX_VALUE;
      for (int i = 0; i < BLOCK_LEN; i++) {
        Object o1 = table[h1 + i];
        Object o2 = table[h2 + i];
        if (o1 == obj || o2 == obj) {
          return;
        }
        if (o1 == null) {
          e1 = Math.min(i, e1);
        }
        if (o2 == null) {
          e2 = Math.min(i, e2);
        }
      }
      if (e1 < e2) {
        table[h1 + e1] = obj;
        return;
      }
      if (e2 != Integer.MAX_VALUE) {
        table[h2 + e2] = obj;
        return;
      }
      if (stashSize < BLOCK_LEN) {
        stash[stashSize] = obj;
        stashSize++;
        return;
      }
      resize();
    }
  }

  public void remove(Object obj) {
    int mask = sectionBlocks - 1;
    int ihash = System.identityHashCode(obj);
    int h1 = BLOCK_LEN * (ihash & mask);
    int h2 = sectionLength + (BLOCK_LEN * (fmix32(ihash) & mask));
    for (int i = 0; i < stashSize; i++) {
      if (obj == stash[i]) {
        if (i == stashSize - 1) {
          stash[i] = null;
        } else {
          stash[i] = stash[stashSize - 1];
          stash[stashSize - 1] = null;
        }
        stashSize--;
        return;
      }
    }
    for (int i = 0; i < BLOCK_LEN; i++) {
      Object o1 = table[h1 + i];
      Object o2 = table[h2 + i];
      if (o1 == obj) {
        table[h1 + i] = null;
        return;
      }
      if (o2 == obj) {
        table[h2 + i] = null;
        return;
      }
    }
  }

  @Override
  public Iterator<Object> iterator() {
    // This is only used by the tests.
    return Stream.concat(Stream.of(stash), Stream.of(table))
            .filter(Objects::nonNull)
            .iterator();
  }

  private static int fmix32(int key) {
    // 32-bit finalization mix of murmur3.
    key ^= key >>> 16;
    key *= 0x85ebca6b;
    key ^= key >>> 13;
    key *= 0xc2b2ae35;
    key ^= key >>> 16;
    return key;
  }

  private void resize() {
    Object[] stashCopy = Arrays.copyOfRange(stash, 0, stashSize);
    Arrays.fill(stash, null);
    stashSize = 0;
    Object[] oldTable = table;
    sectionLength *= 2;
    sectionBlocks *= 2;
    table = new Object[sectionLength * 2];
    for (Object obj : stashCopy) {
      add(obj);
    }
    for (Object obj : oldTable) {
      add(obj);
    }
  }
}
