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
package stormpot.tests;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;
import stormpot.internal.BSlot;
import stormpot.internal.PreciseLeakDetector;
import stormpot.tests.extensions.ExecutorExtension;
import testkits.GarbageCreator;
import testkits.GenericPoolable;

import java.lang.management.GarbageCollectorMXBean;
import java.lang.management.ManagementFactory;
import java.lang.ref.Reference;
import java.util.List;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.atomic.AtomicLong;

import static org.assertj.core.api.Assertions.assertThat;

@SuppressWarnings("unchecked")
class PreciseLeakDetectorTest {
  @RegisterExtension
  final ExecutorExtension executor = new ExecutorExtension();

  private PreciseLeakDetector detector;

  @BeforeEach
  void setUp() {
    detector = new PreciseLeakDetector();
  }

  @Test
  void mustHandleManyAddedReplacedAndRemovedObjects() {
    BlockingQueue<BSlot<GenericPoolable>> queue = new LinkedBlockingQueue<>();
    AtomicLong poisonedSlots = new AtomicLong();
    GenericPoolable[] objs = new GenericPoolable[100000];

    // Adding
    for (int i = 0; i < objs.length; i++) {
      BSlot<GenericPoolable> slot = new BSlot<>(queue, poisonedSlots);
      slot.obj = new GenericPoolable(slot);
      objs[i] = slot.obj;
      detector.register(slot);
    }

    // Replacing
    for (int i = 0; i < objs.length; i++) {
      GenericPoolable a = objs[i];
      BSlot<GenericPoolable> slot = (BSlot<GenericPoolable>) a.getSlot();
      detector.unregister(slot);
      GenericPoolable b = new GenericPoolable(slot);
      slot.obj = b;
      objs[i] = b;
      detector.register(slot);
    }

    // Removing
    for (GenericPoolable obj : objs) {
      detector.unregister((BSlot<?>) obj.getSlot());
    }

    // We should see no leaks
    //noinspection UnusedAssignment
    objs = null;
    gc();

    assertThat(detector.countLeakedObjects()).isZero();
  }

  @Test
  void mustCountCorrectlyAfterAddLeakAddLeakRemove() throws Exception {
    BlockingQueue<BSlot<GenericPoolable>> queue = new LinkedBlockingQueue<>();
    AtomicLong poisonedSlots = new AtomicLong();
    GenericPoolable[] first = new GenericPoolable[1000];
    for (int i = 0; i < first.length; i++) {
      BSlot<GenericPoolable> slot = new BSlot<>(queue, poisonedSlots);
      GenericPoolable obj = new GenericPoolable(slot);
      slot.obj = obj;
      first[i] = obj;
      detector.register(slot);
    }
    first[100] = null;
    first[500] = null;
    first[900] = null;
    gc();

    GenericPoolable[] second = new GenericPoolable[10000];
    for (int i = 0; i < second.length; i++) {
      BSlot<GenericPoolable> slot = new BSlot<>(queue, poisonedSlots);
      GenericPoolable obj = new GenericPoolable(slot);
      slot.obj = obj;
      second[i] = obj;
      detector.register(slot);
    }
    second[1000] = null;
    second[5000] = null;
    second[9000] = null;
    gc();

    for (GenericPoolable obj : first) {
      if (obj != null) {
        detector.unregister((BSlot<?>) obj.getSlot());
      }
    }

    GenericPoolable[] third = new GenericPoolable[10000];
    for (int i = 0; i < third.length; i++) {
      BSlot<GenericPoolable> slot = new BSlot<>(queue, poisonedSlots);
      GenericPoolable obj = new GenericPoolable(slot);
      slot.obj = obj;
      third[i] = obj;
      detector.register((BSlot<?>) obj.getSlot());
    }
    third[1000] = null;
    third[5000] = null;
    third[9000] = null;

    try (AutoCloseable ignore = GarbageCreator.forkCreateGarbage(executor)) {
      int i = 0;
      do {
        GarbageCreator.awaitReferenceProcessing();
      } while (++i < 10 && detector.countLeakedObjects() < 9);
    }

    assertThat(detector.countLeakedObjects()).isEqualTo(9L);
    Reference.reachabilityFence(first);
    Reference.reachabilityFence(second);
    Reference.reachabilityFence(third);
  }

  private void gc() {
    List<GarbageCollectorMXBean> collectors = ManagementFactory.getGarbageCollectorMXBeans();
    long collectionsBefore = collectors.stream().mapToLong(GarbageCollectorMXBean::getCollectionCount).sum();
    System.gc();
    long collectionsAfter = collectors.stream().mapToLong(GarbageCollectorMXBean::getCollectionCount).sum();
    while (collectionsAfter == collectionsBefore) {
      try {
        GarbageCreator.createGarbage();
        Thread.sleep(10);
      } catch (InterruptedException e) {
        throw new RuntimeException(e);
      }
      collectionsAfter = collectors.stream().mapToLong(GarbageCollectorMXBean::getCollectionCount).sum();
    }
  }
}
