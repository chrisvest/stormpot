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

import org.junit.Test;
import org.junit.experimental.categories.Category;
import stormpot.slow.SlowTest;

import java.util.HashSet;
import java.util.Iterator;
import java.util.Random;
import java.util.Set;

import static org.hamcrest.Matchers.is;
import static org.junit.Assert.assertThat;

@Category(SlowTest.class)
public class PreciseLeakDetectorIT {
  private PreciseLeakDetector detector = new PreciseLeakDetector();

  @Test
  public void
  mustCountCorrectlyAfterRandomAddRemoveLeakAndCounts() {
    System.out.print(
        "NOTE: this test takes about 3 to 5 minutes to run...    ");

    // This particular seed seems to give pretty good coverage:
    Random rng = new Random(-6406176578229504295L);
    Set<Object> objs = new HashSet<>();
    long leaksCreated = 0;

    // This distribution of the operations seems to give a good coverage:
    int iterations = 120000;
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
        assertThat(detector.countLeakedObjects(), is(leaksCreated));
      } else {
        // Leak
        Object obj = removeRandom(objs);
        if (obj != null) {
          //noinspection UnusedAssignment : required for System.gc()
          obj = null;
          System.gc();
          leaksCreated++;
        }
      }

      printProgress(iterations, i);
    }
    System.out.println();
    for (Object obj : objs) {
      detector.unregister(obj);
    }
    objs.clear();
    //noinspection UnusedAssignment : required for System.gc()
    objs = null;

    System.gc();
    assertThat(detector.countLeakedObjects(), is(leaksCreated));
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
