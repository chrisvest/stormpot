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
package stormpot.tests;

import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import stormpot.internal.IdentityHashSet;

import java.util.Arrays;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.Set;
import java.util.concurrent.ThreadLocalRandom;
import java.util.stream.IntStream;

import static org.junit.jupiter.api.Assertions.assertTrue;

class IdentityHashSetTest {
  static IntStream setSizes() {
    // Do roughly 100 tests.
    ThreadLocalRandom rng = ThreadLocalRandom.current();
    return IntStream.range(1, 5000).filter(
            i -> rng.nextDouble() < (i < 1000 ? 0.1 : 0.02));
  }

  private static Object[] createObjects(int numberOfObjects) {
    Object[] objs = new Object[numberOfObjects];
    for (int i = 0; i < objs.length; i++) {
      objs[i] = new Object();
      System.identityHashCode(objs[i]);
    }
    return objs;
  }

  private static void assertSetContainsAll(IdentityHashSet set, Object[] objs) {
    Set<Object> reference = referenceSet(objs);
    assertSetsEqual(set, reference);
  }

  private static void assertSetsEqual(IdentityHashSet set, Set<Object> reference) {
    for (Object obj : set) {
      assertTrue(reference.remove(obj));
    }
    assertTrue(reference.isEmpty());
  }

  private static Set<Object> referenceSet(Object[] objs) {
    Set<Object> comparison = Collections.newSetFromMap(new IdentityHashMap<>());
    comparison.addAll(Arrays.asList(objs));
    return comparison;
  }

  @ParameterizedTest
  @MethodSource("setSizes")
  void addingOnce(int numberOfObjects) {
    IdentityHashSet set = new IdentityHashSet();
    Object[] objs = createObjects(numberOfObjects);
    for (Object obj : objs) {
      set.add(obj);
    }
    assertSetContainsAll(set, objs);
  }

  @ParameterizedTest
  @MethodSource("setSizes")
  void addingTwice(int numberOfObjects) {
    IdentityHashSet set = new IdentityHashSet();
    Object[] objs = createObjects(numberOfObjects);
    for (Object obj : objs) {
      set.add(obj);
    }
    Collections.shuffle(Arrays.asList(objs));
    for (Object obj : objs) {
      set.add(obj);
    }
    assertSetContainsAll(set, objs);
  }

  @ParameterizedTest
  @MethodSource("setSizes")
  void addRemove(int numberOfObjects) {
    IdentityHashSet set = new IdentityHashSet();
    Object[] objs = createObjects(numberOfObjects);
    for (Object obj : objs) {
      set.add(obj);
    }
    Collections.shuffle(Arrays.asList(objs));
    for (Object obj : objs) {
      set.remove(obj);
    }
    assertSetContainsAll(set, new Object[0]);
  }

  @ParameterizedTest
  @MethodSource("setSizes")
  void addRemoveRandom(int numberOfObjects) {
    IdentityHashSet set = new IdentityHashSet();
    Object[] objs = createObjects(numberOfObjects);
    for (Object obj : objs) {
      set.add(obj);
    }
    ThreadLocalRandom rng = ThreadLocalRandom.current();
    Set<Object> refSet = referenceSet(objs);
    for (int i = 0; i < numberOfObjects * 3; i++) {
      Object obj = objs[rng.nextInt(0, objs.length)];
      if (rng.nextBoolean()) {
        refSet.remove(obj);
        set.remove(obj);
      } else {
        refSet.add(obj);
        set.add(obj);
      }
    }
    assertSetsEqual(set, refSet);
  }
}
