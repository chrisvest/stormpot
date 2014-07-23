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

import java.util.HashSet;
import java.util.Iterator;
import java.util.Random;
import java.util.Set;

import static org.hamcrest.Matchers.is;
import static org.junit.Assert.assertThat;

public class PreciseLeakDetectorIT {
  private PreciseLeakDetector detector = new PreciseLeakDetector();

  // Note: this test takes about 3 to 4 minutes to run.
  @Test
  public void
  mustCountCorrectlyAfterRandomAddRemoveLeakAndCounts() {
    // This particular seed seems to give pretty good coverage:
    Random rng = new Random(-6406176578229504295L);
    Set<Object> objs = new HashSet<Object>();
    long leaksCreated = 0;

    // This distribution of the operations seems to give a good coverage:
    for (int i = 0; i < 120000; i++) {
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
          obj = null;
          System.gc();
          leaksCreated++;
        }
      }
    }
    for (Object obj : objs) {
      detector.unregister(obj);
    }
    objs.clear();
    objs = null;

    System.gc();
    assertThat(detector.countLeakedObjects(), is(leaksCreated));
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
