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

import org.junit.jupiter.api.Test;
import stormpot.internal.BSlot;
import stormpot.internal.PreciseLeakDetector;
import testkits.GarbageCreator;
import testkits.GenericPoolable;

import java.util.HashSet;
import java.util.Iterator;
import java.util.Random;
import java.util.Set;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.atomic.AtomicLong;

import static org.assertj.core.api.Assertions.assertThat;

class PreciseLeakDetectorIT {
  private final PreciseLeakDetector detector = new PreciseLeakDetector();

  @Test
  void mustCountCorrectlyAfterRandomAddRemoveLeakAndCounts() throws Exception {
    System.out.print(
        "NOTE: this test takes about 15 to 30 seconds to run...    ");

    // This particular seed seems to give pretty good coverage:
    Random rng = new Random(-6406176578229504295L);
    BlockingQueue<BSlot<GenericPoolable>> queue = new LinkedBlockingQueue<>();
    AtomicLong poisonedSlots = new AtomicLong();
    Set<GenericPoolable> objs = new HashSet<>();
    long leaksCreated = 0;

    // This distribution of the operations seems to give a good coverage:
    int iterations = 12000;
    for (int i = 0; i < iterations; i++) {
      int choice = rng.nextInt(100);
      if (choice < 60) {
        // Add
        BSlot<GenericPoolable> slot = new BSlot<>(queue, poisonedSlots);
        GenericPoolable add = new GenericPoolable(slot);
        slot.obj = add;
        objs.add(add);
        detector.register(slot);
      } else if (choice < 80) {
        // Remove
        GenericPoolable remove = removeRandom(objs);
        if (remove != null) {
          detector.unregister((BSlot<?>) remove.getSlot());
        }
      } else if (choice < 90) {
        // Count
        try (AutoCloseable ignore = GarbageCreator.forkCreateGarbage()) {
          GarbageCreator.awaitReferenceProcessing();
          assertThat(detector.countLeakedObjects())
                  .isGreaterThan(leaksCreated - 10)
                  .isLessThanOrEqualTo(leaksCreated);
        }
      } else {
        long gcs = GarbageCreator.countGarbageCollections();
        // Leak
        GenericPoolable obj = removeRandom(objs);
        if (obj != null) {
          //noinspection UnusedAssignment : required for System.gc()
          obj = null;
          do {
            GarbageCreator.createGarbage();
            System.gc();
          } while (gcs == GarbageCreator.countGarbageCollections());
          leaksCreated++;
        }
      }

      printProgress(iterations, i);
    }
    System.out.println();
    for (GenericPoolable obj : objs) {
      detector.unregister((BSlot<?>) obj.getSlot());
    }
    long gcs = GarbageCreator.countGarbageCollections();
    objs.clear();
    //noinspection UnusedAssignment : required for System.gc()
    objs = null;

    do {
      System.gc();
    } while (gcs == GarbageCreator.countGarbageCollections());
    try (AutoCloseable ignore = GarbageCreator.forkCreateGarbage()) {
      GarbageCreator.awaitReferenceProcessing();
    }
    assertThat(detector.countLeakedObjects()).isEqualTo(leaksCreated);
  }

  private static void printProgress(int iterations, int i) {
    int stride = iterations / 100;
    int progress = i / stride;

    if (progress * stride == i - 1) {
      System.out.printf("\033[4D% 3d%%", progress);
    }
  }

  private GenericPoolable removeRandom(Set<GenericPoolable> objs) {
    Iterator<GenericPoolable> iterator = objs.iterator();
    if (iterator.hasNext()) {
      GenericPoolable obj = iterator.next();
      iterator.remove();
      return obj;
    }
    return null;
  }
}
