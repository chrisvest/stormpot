/*
 * Copyright © 2011 Chris Vest (mr.chrisvest@gmail.com)
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
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.LongAdder;
import java.util.function.ToIntFunction;

/**
 * Leak detector, used to detect if objects allocated by the pool get garbage collected,
 * without first being deallocated by the pool.
 */
public final class PreciseLeakDetector {
  private static final ToIntFunction<Object> REF_HASH = obj -> ((CountedPhantomRef<?>) obj).id;

  private final ReferenceQueue<Object> referenceQueue;
  private final LongAdder leakedObjectCount;
  private final IdentityHashSet refs;

  /**
   * Create a new instance.
   */
  public PreciseLeakDetector() {
    referenceQueue = new ReferenceQueue<>();
    leakedObjectCount = new LongAdder();
    refs = new IdentityHashSet(REF_HASH);
  }

  /**
   * Register the given slot and its contained object with the detector.
   * <p>
   * The slot has had an object allocated to it, and is about to be handed over to user code.
   *
   * @param slot The slot to register.
   */
  public void register(BSlot<?> slot) {
    CountedPhantomRef<Object> ref = new CountedPhantomRef<>(slot.obj, referenceQueue);
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

  /**
   * Deregister the given slot from the leak detector.
   * <p>
   * The object held by the slot has been deallocated by the pool.
   *
   * @param slot The slot to deregister.
   */
  public void unregister(BSlot<?> slot) {
    slot.leakCheck.clear();
    synchronized (refs) {
      refs.remove(slot.leakCheck);
    }
    slot.leakCheck = null;
    accumulateLeaks();
  }

  /**
   * Compute a count of the leaked objects that this detector has detected.
   * @return The number of object leaks observed.
   */
  public long countLeakedObjects() {
    accumulateLeaks();
    return leakedObjectCount.sum();
  }

  private static final class CountedPhantomRef<T> extends PhantomReference<T> {
    private static final AtomicInteger COUNTER = new AtomicInteger();

    private final int id; // This hides in alignment padding on most JVMs, including Lilliput.

    CountedPhantomRef(T referent, ReferenceQueue<? super T> q) {
      super(referent, q);
      id = COUNTER.getAndIncrement();
    }
  }
}
