/*
 * Copyright © Chris Vest (mr.chrisvest@gmail.com)
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
import java.util.concurrent.ThreadLocalRandom;
import java.util.function.ToIntFunction;
import java.util.stream.Stream;
import java.util.stream.StreamSupport;

import static java.util.Objects.requireNonNull;

/**
 * Like {@link java.util.IdentityHashMap}, but as a Set with no overhead for storing values.
 */
public final class IdentityHashSet implements Iterable<Object> {
  // Implementation is based on open-addressing 2-choice hashing,
  // with 8-element buckets, and an 8-element stash for unresolvable collisions.

  private static final int BLOCK_LEN = 8;
  private static final ToIntFunction<Object> DEFAULT_HASH = System::identityHashCode;

  private final Object[] stash;
  private final int subtreeMix;
  private final ToIntFunction<Object> hashOf;
  private int stashSize;
  private int sectionLength;
  private int sectionBlocks;
  private Object[] table;
  private boolean treeMode;

  /**
   * Create an empty identity hash set that uses {@link System#identityHashCode(Object)} as the hash code for entries.
   */
  public IdentityHashSet() {
    this(DEFAULT_HASH);
  }

  /**
   * Create an empty identity hash set that uses the given function as the hash code for entries.
   * @param hashCode A function that converts a given object into a reasonably unique integer.
   */
  public IdentityHashSet(ToIntFunction<Object> hashCode) {
    stash = new Object[BLOCK_LEN];
    sectionBlocks = 8;
    sectionLength = BLOCK_LEN * sectionBlocks;
    table = new Object[sectionLength * 2];
    subtreeMix = ThreadLocalRandom.current().nextInt();
    this.hashOf = requireNonNull(hashCode, "hashCode");
  }

  /**
   * Add the given object to the set.
   * @param obj The object to add.
   */
  public void add(Object obj) {
    for (;;) {
      if (treeMode) {
        subtree(obj).add(obj);
        return;
      }
      int mask = sectionBlocks - 1;
      int ihash = hashOf.applyAsInt(obj);
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
      if (e1 <= e2 && e1 != Integer.MAX_VALUE) {
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

  /**
   * Remove the given object from the set.
   * @param obj The object to remove.
   */
  public void remove(Object obj) {
    if (treeMode) {
      subtree(obj).remove(obj);
      return;
    }
    int ihash = hashOf.applyAsInt(obj);
    int mask = sectionBlocks - 1;
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

  private IdentityHashSet subtree(Object obj) {
    int ihash = hashOf.applyAsInt(obj);
    ihash = fmix32(ihash ^ subtreeMix) & sectionBlocks;
    return ((IdentityHashSet) table[ihash]);
  }

  @Override
  public Iterator<Object> iterator() {
    // This is only used by the tests.
    if (treeMode) {
      return Stream.of(table)
              .flatMap(obj -> {
                IdentityHashSet set = (IdentityHashSet) obj;
                return StreamSupport.stream(set.spliterator(), false);
              })
              .iterator();
    } else {
      return Stream.concat(Stream.of(stash), Stream.of(table))
              .filter(Objects::nonNull)
              .iterator();
    }
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
    int newSectionLength = sectionLength * 2;
    int newTableLength = newSectionLength * 2;
    int newSectionBlocks = sectionBlocks * 2;
    Object[] stashCopy = Arrays.copyOfRange(stash, 0, stashSize);
    Arrays.fill(stash, null);
    stashSize = 0;
    Object[] oldTable = table;

    if (newTableLength >= 2_097_152) {
      treeMode = true;
      int len = 16384;
      Object[] newTable = new Object[len];
      for (int i = 0; i < len; i++) {
        newTable[i] = new IdentityHashSet(hashOf);
      }
      table = newTable;
      sectionLength = len;
      sectionBlocks = len - 1;
    } else {
      sectionLength = newSectionLength;
      sectionBlocks = newSectionBlocks;
      table = new Object[newTableLength];
    }

    for (Object obj : stashCopy) {
      add(obj);
    }
    for (Object obj : oldTable) {
      if (obj != null) {
        add(obj);
      }
    }
  }
}
