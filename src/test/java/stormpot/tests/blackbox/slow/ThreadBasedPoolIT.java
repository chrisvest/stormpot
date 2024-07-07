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
package stormpot.tests.blackbox.slow;

import org.junit.jupiter.api.Test;
import testkits.ExpireKit;
import testkits.GenericPoolable;
import stormpot.Slot;

import java.lang.management.ManagementFactory;
import java.lang.management.ThreadMXBean;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.LinkedList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assumptions.assumeTrue;
import static testkits.AlloKit.$countDown;
import static testkits.AlloKit.$new;
import static testkits.AlloKit.$null;
import static testkits.AlloKit.$release;
import static testkits.AlloKit.Action;
import static testkits.AlloKit.alloc;
import static testkits.AlloKit.allocator;
import static testkits.AlloKit.dealloc;
import static testkits.ExpireKit.$expiredIf;
import static testkits.ExpireKit.expire;

abstract class ThreadBasedPoolIT extends PoolIT {

  @org.junit.jupiter.api.Timeout(160)
  @Test
  void backgroundExpirationMustDoNothingWhenPoolIsDepleted() throws Exception {
    AtomicBoolean hasExpired = new AtomicBoolean();
    ExpireKit.CountingExpiration expiration = expire($expiredIf(hasExpired));
    builder.setExpiration(expiration);
    builder.setBackgroundExpirationEnabled(true);

    createPool();

    // Do a thread-local reclaim, if applicable, to keep the object in
    // circulation
    pool.claim(longTimeout).release();
    GenericPoolable obj = pool.claim(longTimeout);
    int expirationsCount = expiration.countExpirations();

    hasExpired.set(true);

    Thread.sleep(1000);

    assertThat(allocator.countDeallocations()).isZero();
    assertThat(expiration.countExpirations()).isEqualTo(expirationsCount);
    obj.release();
  }

  @Test
  void backgroundExpirationMustNotFailWhenThereAreNoObjectsInCirculation()
      throws Exception {
    AtomicBoolean hasExpired = new AtomicBoolean();
    ExpireKit.CountingExpiration expiration = expire($expiredIf(hasExpired));
    builder.setExpiration(expiration);
    builder.setBackgroundExpirationEnabled(true);

    createPool();

    GenericPoolable obj = pool.claim(longTimeout);
    int expirationsCount = expiration.countExpirations();

    hasExpired.set(true);

    Thread.sleep(1000);

    assertThat(allocator.countDeallocations()).isZero();
    assertThat(expiration.countExpirations()).isEqualTo(expirationsCount);
    obj.release();
  }

  @org.junit.jupiter.api.Timeout(160)
  @Test
  void decreasingSizeOfDepletedPoolMustOnlyDeallocateAllocatedObjects()
      throws Exception {
    int startingSize = 256;
    CountDownLatch startLatch = new CountDownLatch(startingSize);
    Semaphore semaphore = new Semaphore(0);
    allocator = allocator(
        alloc($countDown(startLatch, $new)),
        dealloc($release(semaphore, $null)));
    builder.setSize(startingSize).setBackgroundExpirationCheckDelay(10);
    builder.setAllocator(allocator);
    createPool();
    startLatch.await();
    List<GenericPoolable> objs = new ArrayList<>();
    for (int i = 0; i < startingSize; i++) {
      objs.add(pool.claim(longTimeout));
    }

    int size = startingSize;
    List<GenericPoolable> subList = objs.subList(0, startingSize - 1);
    for (GenericPoolable obj : subList) {
      size--;
      pool.setTargetSize(size);
      // It's important that the wait mask produces values greater than the
      // allocation threads idle wait time.
      assertFalse(semaphore.tryAcquire(size & 127, TimeUnit.MILLISECONDS));
      obj.release();
      semaphore.acquire();
    }

    List<GenericPoolable> deallocations = allocator.getDeallocations();
    synchronized (deallocations) {
      assertThat(deallocations).containsExactlyElementsOf(subList);
    }

    objs.get(startingSize - 1).release();
  }

  @Test
  void explicitlyExpiredSlotsMustNotCauseBackgroundCPUBurn()
      throws InterruptedException {
    final ThreadMXBean threads = ManagementFactory.getThreadMXBean();
    final AtomicLong lastUserTimeIncrement = new AtomicLong();
    assumeTrue(threads.isCurrentThreadCpuTimeSupported());
    allocator = allocator(alloc(
        measureLastCPUTime(threads, lastUserTimeIncrement)));
    builder.setAllocator(allocator);
    builder.setSize(2);
    createPool();
    GenericPoolable a = pool.claim(longTimeout);
    GenericPoolable b = pool.claim(longTimeout);
    a.expire();
    Thread.sleep(10);
    a.release();
    a = pool.claim(longTimeout);
    long millisecondsAllowedToBurnCPU = 5000;
    Thread.sleep(millisecondsAllowedToBurnCPU);
    b.expire();
    b.release();
    b = pool.claim(longTimeout);
    a.release();
    b.release();
    long millisecondsSpentBurningCPU =
        TimeUnit.NANOSECONDS.toMillis(lastUserTimeIncrement.get());

    assertThat(millisecondsSpentBurningCPU).isLessThan(millisecondsAllowedToBurnCPU / 2);
  }

  private Action measureLastCPUTime(final ThreadMXBean threads, final AtomicLong cpuTimeSum) {
    return new Action() {
      boolean first = true;

      @Override
      public GenericPoolable apply(Slot slot, GenericPoolable obj) throws Exception {
        GenericPoolable poolable;
        if (first) {
          first = false;

          // Don't count all the class loading in the first allocation.
          poolable = $new.apply(slot, obj);

          threads.setThreadCpuTimeEnabled(true);
          long currentThreadUserTime = threads.getCurrentThreadUserTime();
          cpuTimeSum.set(currentThreadUserTime);
        } else {
          long userTime = threads.getCurrentThreadUserTime();
          cpuTimeSum.set(userTime - cpuTimeSum.get());
          poolable = $new.apply(slot, obj);
        }
        return poolable;
      }
    };
  }

  @Test
  void explicitlyExpiredSlotsThatAreDeallocatedThroughPoolShrinkingMustNotCauseBackgroundCPUBurn()
      throws InterruptedException {
    final ThreadMXBean threads = ManagementFactory.getThreadMXBean();
    final AtomicLong lastUserTimeIncrement = new AtomicLong();
    final AtomicLong maxUserTimeIncrement = new AtomicLong();
    assumeTrue(threads.isCurrentThreadCpuTimeSupported());
    allocator = allocator(alloc(new Action() {
      boolean first = true;

      @Override
      public GenericPoolable apply(Slot slot, GenericPoolable obj) throws Exception {
        if (first) {
          first = false;
          GenericPoolable poolable = $new.apply(slot, obj);
          threads.setThreadCpuTimeEnabled(true);
          lastUserTimeIncrement.set(threads.getCurrentThreadUserTime());
          return poolable;
        }
        long userTime = threads.getCurrentThreadUserTime();
        long delta = userTime - lastUserTimeIncrement.get();
        lastUserTimeIncrement.set(delta);
        long existingDelta;
        do {
          existingDelta = maxUserTimeIncrement.get();
        } while (!maxUserTimeIncrement.compareAndSet(
            existingDelta, Math.max(delta, existingDelta)));
        return $new.apply(slot, obj);
      }
    }));
    builder.setAllocator(allocator);
    int size = 30;
    builder.setSize(size);
    createPool();
    LinkedList<GenericPoolable> objs = new LinkedList<>();
    for (int i = 0; i < size; i++) {
      GenericPoolable obj = pool.claim(longTimeout);
      objs.offer(obj);
      obj.expire();
    }
    int newSize = size / 3;
    pool.setTargetSize(newSize);
    Iterator<GenericPoolable> itr = objs.iterator();
    for (int i = size; i >= newSize; i--) {
      itr.next().release();
      itr.remove();
    }
    long millisecondsAllowedToBurnCPU = 5000;
    Thread.sleep(millisecondsAllowedToBurnCPU);
    while (itr.hasNext()) {
      itr.next().release();
    }
    pool.claim(longTimeout).release();

    long millisecondsSpentBurningCPU =
        TimeUnit.NANOSECONDS.toMillis(maxUserTimeIncrement.get());

    assertThat(millisecondsSpentBurningCPU).isLessThan(millisecondsAllowedToBurnCPU / 2);
  }

  @Test
  void explicitlyExpiredButUnreleasedSlotsMustNotCauseBackgroundCPUBurn()
      throws InterruptedException {
    final ThreadMXBean threads = ManagementFactory.getThreadMXBean();
    final AtomicLong lastUserTimeIncrement = new AtomicLong();
    assumeTrue(threads.isCurrentThreadCpuTimeSupported());
    allocator = allocator(alloc(
        measureLastCPUTime(threads, lastUserTimeIncrement)));
    builder.setAllocator(allocator);
    createPool();
    GenericPoolable a = pool.claim(longTimeout);
    a.expire();

    long millisecondsAllowedToBurnCPU = 5000;
    Thread.sleep(millisecondsAllowedToBurnCPU);

    a.release();
    pool.claim(longTimeout).release();

    long millisecondsSpentBurningCPU =
        TimeUnit.NANOSECONDS.toMillis(lastUserTimeIncrement.get());

    assertThat(millisecondsSpentBurningCPU).isLessThan(millisecondsAllowedToBurnCPU / 2);
  }

  @Test
  void mustNotBurnTooMuchCPUWhileThePoolIsWorkingOnShrinking()
      throws InterruptedException {
    final ThreadMXBean threads = ManagementFactory.getThreadMXBean();
    final AtomicLong lastUserTimeIncrement = new AtomicLong();
    int size = 20;
    assumeTrue(threads.isCurrentThreadCpuTimeSupported());
    allocator = allocator(alloc(
        measureLastCPUTime(threads, lastUserTimeIncrement)));
    builder.setAllocator(allocator);
    builder.setSize(size);
    createPool();
    LinkedList<GenericPoolable> objs = new LinkedList<>();
    for (int i = 0; i < size; i++) {
      objs.add(pool.claim(longTimeout));
    }
    pool.setTargetSize(1);

    long millisecondsAllowedToBurnCPU = 5000;
    Thread.sleep(millisecondsAllowedToBurnCPU);

    for (GenericPoolable obj : objs) {
      obj.expire();
      obj.release();
    }
    pool.claim(longTimeout).release();

    long millisecondsSpentBurningCPU =
        TimeUnit.NANOSECONDS.toMillis(lastUserTimeIncrement.get());

    assertThat(millisecondsSpentBurningCPU).isLessThan(millisecondsAllowedToBurnCPU / 2);
  }
}
