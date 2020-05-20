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
package blackbox;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import stormpot.*;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Future;
import java.util.concurrent.Semaphore;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.*;
import static stormpot.AlloKit.*;
import static stormpot.ExpireKit.*;
import static stormpot.UnitKit.*;

abstract class ThreadBasedPoolTest extends AllocatorBasedPoolTest {

  @Test
  void constructorMustThrowIfBackgroundExpirationCheckDelayIsNegative() {
    assertThrows(IllegalArgumentException.class, () -> builder.setBackgroundExpirationCheckDelay(-1));
  }

  /**
   * Prevent the creation of pools with a null ThreadFactory.
   * @see PoolBuilder#setThreadFactory(java.util.concurrent.ThreadFactory)
   */
  @Test
  void constructorMustThrowOnNullThreadFactory() {
    assertThrows(NullPointerException.class, () -> builder.setThreadFactory(null));
  }

  @Test
  void constructorMustThrowIfConfiguredThreadFactoryReturnsNull() {
    ThreadFactory factory = r -> null;
    builder.setThreadFactory(factory);
    assertThrows(NullPointerException.class, this::createPool);
  }

  /**
   * A call to claim with time-out must complete within the time-out period
   * even if the Allocator never returns.
   * We test for this by configuring an Allocator that will never return from
   * any calls to allocate, and then calling claim with a time-out on the pool.
   * This claim-call must then complete before the time-out on the test case
   * itself elapses.
   * @see Pool
   */
  @ParameterizedTest
  @EnumSource(Taps.class)
  void claimMustStayWithinDeadlineEvenIfAllocatorBlocksImmediately(Taps taps) throws Exception {
    Semaphore semaphore = new Semaphore(0);
    allocator = allocator(alloc($acquire(semaphore, $new)));
    builder.setAllocator(allocator);
    createPool();
    PoolTap<GenericPoolable> tap = taps.get(this);
    try {
      tap.claim(shortTimeout);
    } finally {
      semaphore.release(10);
    }
  }

  @SuppressWarnings("SuspiciousMethodCalls")
  @ParameterizedTest
  @EnumSource(Taps.class)
  void backgroundExpirationMustExpireNewlyAllocatedObjectsThatAreNeverClaimed(Taps taps) throws Exception {
    List<GenericPoolable> toExpire = Collections.synchronizedList(new ArrayList<>());
    CountingExpiration expiration = expire(info -> toExpire.contains(info.getPoolable()));
    builder.setExpiration(expiration).setSize(2);
    reducedBackgroundExpirationCheckDelay();
    createPool();
    PoolTap<GenericPoolable> tap = taps.get(this);

    // Make sure one object makes it into the live queue
    tap.claim(longTimeout).release();
    GenericPoolable obj = tap.claim(longTimeout);
    obj.release(); // Turn it into a TLR claim

    while (allocator.countAllocations() < 2) {
      Thread.yield();
    }
    synchronized (allocator.getAllocations()) {
      // Only mark the object we *didn't* claim for expiration
      for (GenericPoolable allocation : allocator.getAllocations()) {
        if (allocation != obj) {
          toExpire.add(allocation);
        }
      }
    }

    // Never claim the other object
    while (allocator.countDeallocations() < 1) {
      Thread.yield();
      if (Thread.interrupted()) {
        throw new InterruptedException();
      }
    }

    synchronized (allocator.getDeallocations()) {
      assertThat(allocator.getDeallocations()).containsAll(toExpire);
    }
  }

  @Test
  void mustProactivelyReallocatePoisonedSlotsWhenAllocatorStopsThrowingExceptions()
      throws Exception {
    final CountDownLatch allocationLatch = new CountDownLatch(1);
    allocator = allocator(alloc(
        $throw(new Exception("boom")),
        $countDown(allocationLatch, $new)));
    builder.setAllocator(allocator);
    createPool();
    allocationLatch.await();
    GenericPoolable obj = pool.claim(longTimeout);
    try {
      assertThat(obj).isNotNull();
    } finally {
      obj.release();
    }
  }

  @Test
  void mustProactivelyReallocatePoisonedSlotsWhenReallocatorStopsThrowingExceptions()
      throws Exception {
    final AtomicBoolean expired = new AtomicBoolean();
    final CountDownLatch allocationLatch = new CountDownLatch(2);
    allocator = reallocator(
        alloc($countDown(allocationLatch, $new)),
        realloc($throw(new Exception("boom")), $new));
    builder.setAllocator(allocator);
    builder.setExpiration(expire($expiredIf(expired)));
    createPool();
    expired.set(false); // first claimed object is not expired
    pool.claim(longTimeout).release(); // first object is fully allocated
    expired.set(true); // the next object we claim is expired
    GenericPoolable obj = pool.claim(zeroTimeout); // send back to reallocation
    assertThat(obj).isNull();
    allocationLatch.await();
    expired.set(false);
    obj = pool.claim(longTimeout);
    try {
      assertThat(obj).isNotNull();
    } finally {
      obj.release();
    }
  }

  @Test
  void mustProactivelyReallocatePoisonedSlotsWhenAllocatorStopsReturningNull()
      throws Exception {
    final CountDownLatch allocationLatch = new CountDownLatch(1);
    allocator = allocator(
        alloc($null, $countDown(allocationLatch, $new)));
    builder.setAllocator(allocator);
    createPool();
    allocationLatch.await();
    GenericPoolable obj = pool.claim(longTimeout);
    try {
      assertThat(obj).isNotNull();
    } finally {
      obj.release();
    }
  }

  @Test
  void mustProactivelyReallocatePoisonedSlotsWhenReallocatorStopsReturningNull()
      throws Exception {
    AtomicBoolean expired = new AtomicBoolean();
    CountDownLatch allocationLatch = new CountDownLatch(1);
    Semaphore fixReallocLatch = new Semaphore(0);
    allocator = reallocator(
        alloc($new,
            $countDown(allocationLatch, $acquire(fixReallocLatch, $new))),
        realloc($null, $new));
    builder.setAllocator(allocator);
    builder.setExpiration(expire($expiredIf(expired)));
    createPool();
    expired.set(false); // first claimed object is not expired
    pool.claim(longTimeout).release(); // first object is fully allocated
    expired.set(true); // the next object we claim is expired
    GenericPoolable obj = pool.claim(zeroTimeout); // send back to reallocation
    assertThat(obj).isNull();
    fixReallocLatch.release();
    allocationLatch.await();
    expired.set(false);
    obj = pool.claim(longTimeout);
    try {
      assertThat(obj).isNotNull();
    } finally {
      obj.release();
    }
  }

  @Test
  void mustCheckObjectExpirationInBackgroundWhenEnabled() throws Exception {
    CountDownLatch latch = new CountDownLatch(1);
    CountingReallocator reallocator = reallocator();
    ExpireKit.CountingExpiration expiration = expire($expired, $countDown(latch, $fresh));
    builder.setExpiration(expiration);
    builder.setAllocator(reallocator);
    createPool();

    latch.await();

    assertThat(reallocator.countAllocations()).isOne();
    assertThat(reallocator.countDeallocations()).isZero();
    assertThat(reallocator.countReallocations()).isOne();
  }

  @Test
  void backgroundExpirationMustExpireObjectsWhenExpirationThrows() throws Exception {
    CountDownLatch latch = new CountDownLatch(1);
    CountingExpiration expiration = expire(
        $throwExpire(new Exception("boom")),
        $countDown(latch, $fresh));
    builder.setExpiration(expiration);
    createPool();

    latch.await();

    assertThat(allocator.countAllocations()).isEqualTo(2);
    assertThat(allocator.countDeallocations()).isOne();
  }

  @Test
  void mustReallocateExplicitlyExpiredObjectsInBackgroundWithBackgroundExpiration()
      throws Exception {
    CountDownLatch latch = new CountDownLatch(2);
    allocator = allocator(alloc($countDown(latch, $new)));
    builder.setAllocator(allocator).setSize(1);
    reducedBackgroundExpirationCheckDelay();
    createPool();

    GenericPoolable obj = pool.claim(longTimeout);
    obj.expire();
    obj.release();

    latch.await();
  }

  @Test
  void mustNotReallocateObjectsThatAreNotExpiredByTheBackgroundCheck()
      throws Exception {
    CountDownLatch latch = new CountDownLatch(2);
    CountingExpiration expiration = expire($countDown(latch, $fresh));
    CountingReallocator reallocator = reallocator();
    builder.setExpiration(expiration);
    reducedBackgroundExpirationCheckDelay();
    createPool();

    latch.await();

    assertThat(reallocator.countReallocations()).isZero();
    assertThat(reallocator.countDeallocations()).isZero();
  }

  @Test
  void poolMustUseConfiguredThreadFactoryWhenCreatingBackgroundThreads()
      throws InterruptedException {
    ThreadFactory delegateThreadFactory = builder.getThreadFactory();
    List<Thread> createdThreads = new ArrayList<>();
    ThreadFactory factory = r -> {
      Thread thread = delegateThreadFactory.newThread(r);
      createdThreads.add(thread);
      return thread;
    };
    builder.setThreadFactory(factory);
    createPool();
    pool.claim(longTimeout).release();
    assertThat(createdThreads.size()).isEqualTo(1);
    assertTrue(createdThreads.get(0).isAlive());
    pool.shutdown().await(longTimeout);
    assertThat(createdThreads.size()).isEqualTo(1);
    Thread thread = createdThreads.get(0);
    thread.join();
    assertFalse(thread.isAlive());
  }

  @ParameterizedTest
  @EnumSource(Taps.class)
  void disregardPileMustNotPreventBackgroundExpirationFromCheckingObjects(Taps taps) throws Exception {
    if (taps == Taps.THREAD_LOCAL) {
      return; // It is not safe to use the thread local tap in this test.
    }
    CountDownLatch firstThreadReady = new CountDownLatch(1);
    CountDownLatch firstThreadPause = new CountDownLatch(1);
    builder.setSize(2);
    reducedBackgroundExpirationCheckDelay();
    createPool();
    PoolTap<GenericPoolable> tap = taps.get(this);

    // Make the first object TLR claimed in another thread.
    Future<?> firstThread = forkFuture(() -> {
      tap.claim(longTimeout).release();
      GenericPoolable obj = tap.claim(longTimeout);
      firstThreadReady.countDown();
      firstThreadPause.await();
      obj.release();
      return null;
    });

    firstThreadReady.await();

    // Move the now TLR claimed object to the front of the queue.
    forkFuture($claimRelease(tap, longTimeout)).get();

    // Now this claim should move the first object into the disregard pile.
    GenericPoolable obj = tap.claim(longTimeout);
    obj.expire(); // Expire the slot, so expiration should pick it up.
    firstThreadPause.countDown();
    firstThread.get();
    allocator.clearLists();
    obj.release(); // And now we allow the allocator thread to get to it.

    // By expiring the objects at this point, we should observe that
    // the 'obj' gets deallocated.
    boolean found;
    do {
      Thread.sleep(10);
      found = allocator.getDeallocations().contains(obj);
    } while (!found);
  }

  @Test
  void mustNotFrivolouslyReallocateNonPoisonedSlotsDuringEagerRecovery()
      throws Exception {
    final CountDownLatch allocationLatch = new CountDownLatch(3);
    allocator = allocator(alloc(
        $countDown(allocationLatch, $null),
        $countDown(allocationLatch, $new)));
    builder.setAllocator(allocator).setSize(2);
    createPool();
    allocationLatch.await();
    // The pool should now be fully allocated and healed
    GenericPoolable a = pool.claim(longTimeout);
    GenericPoolable b = pool.claim(longTimeout);
    try {
      assertThat(allocator.countAllocations()).isEqualTo(3);
      assertThat(allocator.countDeallocations()).isEqualTo(0); // allocation failed
      assertThat(allocator.getDeallocations()).doesNotContain(a, b);
    } finally {
      a.release();
      b.release();
    }
  }

  @ParameterizedTest
  @EnumSource(Taps.class)
  void objectMustBeClaimableAfterBackgroundReallocation(Taps taps) throws Exception {
    CountDownLatch latch = new CountDownLatch(1);
    CountingExpiration expiration =
        expire($countDown(latch, $expired), $fresh);
    builder.setExpiration(expiration);
    createPool();
    PoolTap<GenericPoolable> tap = taps.get(this);

    latch.await();

    tap.claim(longTimeout).release();
  }

  @Test
  void mustReallocateExplicitlyExpiredObjectsInBackgroundWithoutBgExpiration()
      throws Exception {
    CountDownLatch latch = new CountDownLatch(2);
    allocator = allocator(alloc($countDown(latch, $new)));
    builder.setAllocator(allocator).setSize(1);
    reducedBackgroundExpirationCheckDelay();
    noBackgroundExpirationChecking();
    createPool();

    GenericPoolable obj = pool.claim(longTimeout);
    obj.expire();
    obj.release();

    latch.await();
  }

  @ParameterizedTest
  @EnumSource(Taps.class)
  void backgroundExpirationMustNotExpireObjectsThatAreClaimed(Taps taps) throws Exception {
    AtomicBoolean hasExpired = new AtomicBoolean();
    CountDownLatch latch = new CountDownLatch(4);
    CountingExpiration expiration = expire(
        $countDown(latch, $expiredIf(hasExpired)));
    builder.setExpiration(expiration).setSize(2);
    reducedBackgroundExpirationCheckDelay();
    createPool();
    PoolTap<GenericPoolable> tap = taps.get(this);

    // If applicable, do a thread-local reclaim
    tap.claim(longTimeout).release();
    GenericPoolable obj = pool.claim(longTimeout);
    hasExpired.set(true);

    try {
      latch.await();
      hasExpired.set(false);

      List<GenericPoolable> deallocations = allocator.getDeallocations();
      // Synchronized to guard against concurrent modification from the allocator
      //noinspection SynchronizationOnLocalVariableOrMethodParameter
      synchronized (deallocations) {
        assertThat(deallocations).doesNotContain(obj);
      }
    } finally {
      obj.release();
    }
  }

  @Test
  void managedPoolMustRecordObjectLifetimeOnReallocateInConfiguredMetricsRecorder()
      throws InterruptedException {
    builder.setMetricsRecorder(new LastSampleMetricsRecorder());
    Semaphore semaphore = new Semaphore(0);
    builder.setAllocator(reallocator(realloc($acquire(semaphore, $new), $new)));
    AtomicBoolean hasExpired = new AtomicBoolean();
    builder.setExpiration(expire($expiredIf(hasExpired)));
    ManagedPool managedPool = createPool().getManagedPool();
    GenericPoolable obj = pool.claim(longTimeout);
    spinwait(5);
    obj.release();
    hasExpired.set(true);
    assertNull(pool.claim(zeroTimeout));
    hasExpired.set(false);
    semaphore.release(1);
    obj = pool.claim(longTimeout); // wait for reallocation
    try {
      assertThat(managedPool.getObjectLifetimePercentile(0.0))
          .isGreaterThanOrEqualTo(5.0).isNotNaN().isLessThan(50000.0);
    } finally {
      obj.release();
    }
  }
}
