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
package testkits;

import java.lang.management.GarbageCollectorMXBean;
import java.lang.management.ManagementFactory;
import java.lang.ref.PhantomReference;
import java.lang.ref.Reference;
import java.lang.ref.ReferenceQueue;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.atomic.AtomicBoolean;

public class GarbageCreator {
  static volatile long exposer;

  public static void createGarbage() {
    ThreadLocalRandom rng = ThreadLocalRandom.current();
    long collector = 0;
    for (int i = 0; i < 1000; i++) {
      int[] xs = new int[rng.nextInt(1000, 100000)];
      collector ^= System.identityHashCode(xs) * (long) xs.length;
    }
    //noinspection NonAtomicOperationOnVolatileField
    exposer = collector + exposer;
  }

  public static AutoCloseable forkCreateGarbage() {
    AtomicBoolean shutDown = new AtomicBoolean();
    Runnable task = () -> {
      try {
        while (!shutDown.get()) {
          createGarbage();
          System.gc();
          //noinspection BusyWait
          Thread.sleep(10);
        }
      } catch (InterruptedException ignore) {
      }
    };
    Thread thread = new Thread(task);
    thread.setDaemon(true);
    thread.start();
    return () -> {
      thread.interrupt();
      thread.join();
    };
  }

  public static long countGarbageCollections() {
    long count = 0;
    for (GarbageCollectorMXBean bean : ManagementFactory.getGarbageCollectorMXBeans()) {
      count += bean.getCollectionCount();
    }
    return count;
  }

  public static void awaitReferenceProcessing() throws InterruptedException {
    ReferenceQueue<Object> queue = new ReferenceQueue<>();
    PhantomReference<Object> reference = createLeakedObjectReference(queue);
    try {
      queue.remove();
    } finally {
      Reference.reachabilityFence(reference);
    }
  }

  private static PhantomReference<Object> createLeakedObjectReference(ReferenceQueue<Object> queue) {
    return new PhantomReference<>(new Object() /* intentionally leak */, queue);
  }
}
