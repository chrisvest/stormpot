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
import stormpot.ExpireKit;
import stormpot.GenericPoolable;
import stormpot.LastSampleMetricsRecorder;
import stormpot.ManagedPool;
import stormpot.Pool;
import stormpot.PoolBuilder;
import stormpot.PoolException;
import stormpot.PoolTap;
import stormpot.Slot;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Future;
import java.util.concurrent.Semaphore;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;
import static stormpot.AlloKit.$acquire;
import static stormpot.AlloKit.$countDown;
import static stormpot.AlloKit.$if;
import static stormpot.AlloKit.$new;
import static stormpot.AlloKit.$null;
import static stormpot.AlloKit.$throw;
import static stormpot.AlloKit.Action;
import static stormpot.AlloKit.CountingReallocator;
import static stormpot.AlloKit.alloc;
import static stormpot.AlloKit.allocator;
import static stormpot.AlloKit.realloc;
import static stormpot.AlloKit.reallocator;
import static stormpot.ExpireKit.$countDown;
import static stormpot.ExpireKit.$expired;
import static stormpot.ExpireKit.$expiredIf;
import static stormpot.ExpireKit.$fresh;
import static stormpot.ExpireKit.$throwExpire;
import static stormpot.ExpireKit.CountingExpiration;
import static stormpot.ExpireKit.expire;
import static stormpot.UnitKit.$claim;
import static stormpot.UnitKit.$claimRelease;
import static stormpot.UnitKit.capture;
import static stormpot.UnitKit.forkFuture;
import static stormpot.UnitKit.spinwait;

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

  @Test
  void managedPoolMustCountAllocationsFailingWithExceptionsAndBgReallocation() throws Exception {
    CountDownLatch latch = new CountDownLatch(1);
    Exception exception = new Exception("boo");
    builder.setSize(2).setAllocator(allocator(alloc(
        $new,
        $throw(exception),
        $throw(exception),
        $countDown(latch, $new))));
    ManagedPool managedPool = createPool().getManagedPool();

    // simply wait for the proactive healing to replace the failed allocations
    latch.await();

    assertThat(managedPool.getFailedAllocationCount()).isEqualTo(2L);
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
  void claimMustStayWithinDeadlineEvenIfAllocatorBlocks(Taps taps) throws Exception {
    Semaphore semaphore = new Semaphore(1);
    allocator = allocator(alloc($acquire(semaphore, $new)));
    builder.setAllocator(allocator);
    createPool();
    PoolTap<GenericPoolable> tap = taps.get(this);
    GenericPoolable obj = tap.claim(longTimeout);
    obj.expire();
    obj.release();
    try {
      tap.claim(shortTimeout);
    } finally {
      semaphore.release(10);
    }
  }

  /**
   * Likewise as in {@link #mustStillBeUsableAfterExceptionInAllocate}, a pool
   * must not break its internal invariants if a Reallocator throws an exception
   * in reallocate, and it must still be usable after the exception has bubbled out.
   * We test for this by configuring a Reallocator that always throws on
   * reallocate, and we also configure an Expiration that will mark the first
   * slot it checks as expired. Then, when we call claim, that first live slot
   * will be sent back for reallocation, which will throw and poison the slot.
   * Then the slot comes back to our still on-going claim, which throws
   * because of the poison. The slot then gets sent back again, and now,
   * because of the poison, it will not be reallocated, but instead have a
   * fresh Poolable allocated anew. This new good Poolable is what we get out
   * of the last call to claim.
   */
  @ParameterizedTest
  @EnumSource(Taps.class)
  void mustStillBeUsableAfterExceptionInReallocateWithBackgroundThread(Taps taps) throws Exception {
    final AtomicBoolean throwInAllocate = new AtomicBoolean();
    final AtomicBoolean hasExpired = new AtomicBoolean();
    final CountDownLatch allocationLatch = new CountDownLatch(2);
    builder.setAllocator(reallocator(
        alloc($if(throwInAllocate,
            $throw(new RuntimeException("boo")),
            $countDown(allocationLatch, $new))),
        realloc($throw(new RuntimeException("boo")))));
    builder.setExpiration(expire($expiredIf(hasExpired)));
    createPool();
    PoolTap<GenericPoolable> tap = taps.get(this);
    tap.claim(longTimeout).release(); // object now allocated
    throwInAllocate.set(true);
    hasExpired.set(true);
    try {
      tap.claim(longTimeout);
      fail("claim should have thrown");
    } catch (PoolException ignore) {}
    throwInAllocate.set(false);
    hasExpired.set(false);
    allocationLatch.await();
    GenericPoolable claim = tap.claim(longTimeout);
    assertThat(claim).isNotNull();
    claim.release();
  }

  /**
   * Here's the scenario we're trying to target:
   *
   * - You (or your thread) do a successful claim, and triumphantly stores it
   *   in the ThreadLocal cache.
   * - You then return the object after use, so now it's back in the
   *   live-queue for others to grab.
   * - Someone else tries to claim the object, but decides that it has expired,
   *   and sends it off through the dead-queue to be reallocated.
   * - The reallocation fails for some reason, and the slot is now poisoned.
   * - You want to claim an object again, and start by looking in the
   *   ThreadLocal cache.
   * - You find the slot for the object you had last, but the slot is poisoned.
   * - Now, because you found it in the ThreadLocal cache – and notably did
   *   *not* pull it off of the live-queue – you cannot just put it on the
   *   dead-queue, because that could lead to unbounded memory use.
   * - Instead, it has to be marked as live, and we instead have to wait for
   *   someone to pull it off of the live-queue, check the poison again, and
   *   *then* put it on the dead-queue.
   * - Your ThreadLocal reclaim attempt then end in throwing the poison,
   *   wrapped in a PoolException.
   * - Sadly, this process does not involve clearing out the ThreadLocal cache,
   *   so if you quickly catch the exception and try to claim again, you will
   *   find the same exact poisoned slot and go through the same routine, that
   *   ends in a thrown exception and a poisoned slot still left in the
   *   ThreadLocal cache.
   */
  @ParameterizedTest
  @EnumSource(Taps.class)
  void mustNotCachePoisonedSlots(Taps taps) throws Exception {
    // First we prime the possible thread-local cache to a particular object.
    // Then we instruct the allocator to always throw an exception when it is
    // told to allocate on that particular slot.
    // Then, in another thread, we mark all objects in the pool as expired.
    // Once we have observed a reallocation attempt at our primed slot, we
    // try to reclaim it. The reclaim must not throw an exception because of
    // the cached poisoned slot.
    builder.setSize(1);
    noBackgroundExpirationChecking();

    // Enough permits for each initial allocation:
    final Semaphore semaphore = new Semaphore(1);

    final AtomicBoolean hasExpired = new AtomicBoolean(false);
    builder.setExpiration(expire($expiredIf(hasExpired)));

    final String allocationCause = "allocation blew up!";
    final AtomicReference<Slot> failOnAllocatingSlot =
        new AtomicReference<>();
    final AtomicInteger observedFailedAllocation = new AtomicInteger();
    Action observeFailure = (slot, obj) -> {
      if (slot == failOnAllocatingSlot.get()) {
        observedFailedAllocation.incrementAndGet();
        failOnAllocatingSlot.set(null);
        throw new RuntimeException(allocationCause);
      }
      return new GenericPoolable(slot);
    };
    allocator = allocator(alloc($acquire(semaphore, observeFailure)));
    builder.setAllocator(allocator);

    ManagedPool managedPool = createPool().getManagedPool();
    PoolTap<GenericPoolable> tap = taps.get(this);

    // Prime any thread-local cache
    GenericPoolable obj = tap.claim(longTimeout);
    failOnAllocatingSlot.set(obj.getSlot());
    obj.release(); // Places slot at end of queue

    // Expire all poolables
    hasExpired.set(true);
    AtomicReference<GenericPoolable> ref = new AtomicReference<>();
    try {
      forkFuture(capture($claim(tap, shortTimeout), ref)).get();
    } catch (ExecutionException ignore) {
      // This is okay. We just got a failed reallocation
    }
    assertNull(ref.get());

    // Give the allocator enough permits to reallocate the whole pool, again
    semaphore.release(Integer.MAX_VALUE);

    // Wait for our primed slot to get reallocated
    while (managedPool.getAllocationCount() < 2) {
      Thread.yield();
    }
    while (managedPool.getFailedAllocationCount() < 1) {
      Thread.yield();
    }

    // Things no longer expire...
    hasExpired.set(false);

    // ... so we should be able to claim without trouble
    tap.claim(longTimeout).release();
  }

  @ParameterizedTest
  @EnumSource(Taps.class)
  void newBgAllocatedObjectsMustBeClaimedAheadOfExistingLiveObjects(Taps taps) throws Exception {
    reducedBackgroundExpirationCheckDelay();
    createPoolOfSize(3);
    PoolTap<GenericPoolable> tap = taps.get(this);

    GenericPoolable a = tap.claim(longTimeout);
    GenericPoolable b = tap.claim(longTimeout);
    GenericPoolable c = tap.claim(longTimeout);
    c.expire(); // 'c' is the current TLR claim, so expire that to avoid the TLR cache.
    b.release();
    a.release();
    c.release();

    while (pool.getManagedPool().getAllocationCount() < 4) {
      Thread.yield();
    }

    GenericPoolable d = tap.claim(longTimeout);
    try {
      assertThat(d).as("%s should be different from %s, %s, and %s.", d, a, b, c)
          .isNotSameAs(a).isNotSameAs(b).isNotSameAs(c);
    } finally {
      d.release();
    }
  }

  @Override
  void claimWhenInterruptedMustNotThrowIfObjectIsAvailableViaCache(Taps taps) throws Exception {
    noBackgroundExpirationChecking(); // Prevent background expiration checking from claiming cached object.
    super.claimWhenInterruptedMustNotThrowIfObjectIsAvailableViaCache(taps);
  }

  @Override
  void mustNotHoldOnToDeallocatedObjectsWhenLeakDetectionIsEnabled() throws Exception {
    noBackgroundExpirationChecking();
    super.mustNotHoldOnToDeallocatedObjectsWhenLeakDetectionIsEnabled();
  }

  @Override
  void tryClaimMustReturnIfPoolIsNotEmpty(Taps taps) throws Exception {
    noBackgroundExpirationChecking();
    super.tryClaimMustReturnIfPoolIsNotEmpty(taps);
  }

  @Override
  void managedPoolMustGiveNumberOfAllocatedAndInUseObjects() throws Exception {
    noBackgroundExpirationChecking();
    super.managedPoolMustGiveNumberOfAllocatedAndInUseObjects();
  }
}
