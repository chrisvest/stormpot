/*
 * Copyright © 2011-2019 Chris Vest (mr.chrisvest@gmail.com)
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
package stormpot;

import org.junit.jupiter.api.Test;

import java.lang.management.GarbageCollectorMXBean;
import java.util.*;

import static java.lang.management.ManagementFactory.getGarbageCollectorMXBeans;
import static org.assertj.core.api.Assertions.assertThat;

class PreciseLeakDetectorIT {
  private final PreciseLeakDetector detector = new PreciseLeakDetector();
  private final List<GarbageCollectorMXBean> gcBeans = getGarbageCollectorMXBeans();

  @Test
  void mustCountCorrectlyAfterRandomAddRemoveLeakAndCounts() {
    System.out.print(
        "NOTE: this test takes about 15 to 30 seconds to run...    ");

    // This particular seed seems to give pretty good coverage:
    Random rng = new Random(-6406176578229504295L);
    Set<Object> objs = new HashSet<>();
    long leaksCreated = 0;

    // This distribution of the operations seems to give a good coverage:
    int iterations = 12000;
    for (int i = 0; i < iterations; i++) {
      int choice = rng.nextInt(100);
      if (choice < 60) {
        // Add
        Object add = new Object();
        objs.add(add);
        detector.register(add);
      } else if (choice < 80) {
        // Remove
        Object remove = removeRandom(objs);
        if (remove != null) {
          detector.unregister(remove);
        }
      } else if (choice < 90) {
        // Count
        assertThat(detector.countLeakedObjects())
            .isGreaterThan(leaksCreated - 10)
            .isLessThanOrEqualTo(leaksCreated);
      } else {
        long gcs = sumGarbageCollections();
        // Leak
        Object obj = removeRandom(objs);
        if (obj != null) {
          //noinspection UnusedAssignment : required for System.gc()
          obj = null;
          do {
            System.gc();
          } while (gcs == sumGarbageCollections());
          leaksCreated++;
        }
      }

      printProgress(iterations, i);
    }
    System.out.println();
    for (Object obj : objs) {
      detector.unregister(obj);
    }
    long gcs = sumGarbageCollections();
    objs.clear();
    //noinspection UnusedAssignment : required for System.gc()
    objs = null;

    do {
      System.gc();
    } while (gcs == sumGarbageCollections());
    assertThat(detector.countLeakedObjects()).isEqualTo(leaksCreated);
  }

  private long sumGarbageCollections() {
    return gcBeans.stream().mapToLong(
        GarbageCollectorMXBean::getCollectionCount).sum();
  }

  private static void printProgress(int iterations, int i) {
    int stride = iterations / 100;
    int progress = i / stride;

    if (progress * stride == i - 1) {
      System.out.printf("\033[4D% 3d%%", progress);
    }
  }

  private Object removeRandom(Set<Object> objs) {
    Iterator<Object> iterator = objs.iterator();
    if (iterator.hasNext()) {
      Object obj = iterator.next();
      iterator.remove();
      return obj;
    }
    return null;
  }
}
