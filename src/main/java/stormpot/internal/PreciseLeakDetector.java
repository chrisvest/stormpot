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

import java.lang.ref.PhantomReference;
import java.lang.ref.Reference;
import java.lang.ref.ReferenceQueue;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.LongAdder;

public final class PreciseLeakDetector {
  private final ReferenceQueue<Object> referenceQueue;
  private final LongAdder leakedObjectCount;
  private final IdentityHashSet refs;

  public PreciseLeakDetector() {
    referenceQueue = new ReferenceQueue<>();
    leakedObjectCount = new LongAdder();
    refs = new IdentityHashSet();
  }

  public void register(BSlot<?> slot) {
    PhantomReference<Object> ref = new PhantomReference<>(slot.obj, referenceQueue);
    slot.leakCheck = ref;
    synchronized (refs) {
      refs.add(ref);
    }
    accumulateLeaks();
  }

  private void accumulateLeaks() {
    List<Reference<?>> refsToRemove = null;
    Reference<?> ref;
    while ((ref = referenceQueue.poll()) != null) {
      if (refsToRemove == null) {
        refsToRemove = new ArrayList<>();
      }
      refsToRemove.add(ref);
    }
    if (refsToRemove != null) {
      leakedObjectCount.add(refsToRemove.size());
      synchronized (refs) {
        for (Reference<?> toRemove : refsToRemove) {
          refs.remove(toRemove);
        }
      }
    }
  }

  public void unregister(BSlot<?> slot) {
    slot.leakCheck.clear();
    synchronized (refs) {
      refs.remove(slot.leakCheck);
    }
    slot.leakCheck = null;
    accumulateLeaks();
  }

  public long countLeakedObjects() {
    accumulateLeaks();
    return leakedObjectCount.sum();
  }
}
