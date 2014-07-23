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

import java.util.Iterator;
import java.util.Set;

import static org.hamcrest.Matchers.is;
import static org.junit.Assert.assertThat;

public class PreciseLeakDetectorTest {
  public PreciseLeakDetector detector = new PreciseLeakDetector();

  @Test public void
  mustHandleManyAddedReplacedAndRemovedObjects() {
    Object[] objs = new Object[100000];

    // Adding
    for (int i = 0; i < objs.length; i++) {
      Object obj = new Object();
      objs[i] = obj;
      detector.register(obj);
    }

    // Replacing
    for (int i = 0; i < objs.length; i++) {
      Object a = objs[i];
      Object b = new Object();
      objs[i] = b;
      detector.unregister(a);
      detector.register(b);
    }

    // Removing
    for (int i = 0; i < objs.length; i++) {
      detector.unregister(objs[i]);
    }

    // We should see no leaks
    objs = null;
    System.gc();

    assertThat(detector.countLeakedObjects(), is(0L));
  }

  @Test public void
  mustCountCorrectlyAfterAddLeakAddLeakRemove() {
    Object[] first = new Object[1000];
    for (int i = 0; i < first.length; i++) {
      Object obj = new Object();
      first[i] = obj;
      detector.register(obj);
    }
    first[100] = null;
    first[500] = null;
    first[900] = null;
    System.gc();

    Object[] second = new Object[10000];
    for (int i = 0; i < second.length; i++) {
      Object obj = new Object();
      second[i] = obj;
      detector.register(obj);
    }
    second[1000] = null;
    second[5000] = null;
    second[9000] = null;
    System.gc();

    for (int i = 0; i < first.length; i++) {
      Object obj = first[i];
      if (obj != null) {
        detector.unregister(obj);
      }
    }

    Object[] third = new Object[10000];
    for (int i = 0; i < third.length; i++) {
      Object obj = new Object();
      third[i] = obj;
      detector.register(obj);
    }
    third[1000] = null;
    third[5000] = null;
    third[9000] = null;
    System.gc();

    assertThat(detector.countLeakedObjects(), is(9L));
  }
}
