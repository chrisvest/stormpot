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
package stormpot.tests.blackbox;

import org.assertj.core.data.Offset;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import stormpot.BasePoolable;
import stormpot.internal.NanoClock;
import testkits.AlloKit;
import stormpot.Allocator;
import stormpot.Completion;
import stormpot.Expiration;
import testkits.FixedMeanMetricsRecorder;
import testkits.GarbageCreator;
import testkits.GenericPoolable;
import testkits.LastSampleMetricsRecorder;
import stormpot.ManagedPool;
import stormpot.Pool;
import stormpot.PoolBuilder;
import stormpot.PoolException;
import stormpot.PoolTap;
import stormpot.Poolable;
import stormpot.Reallocator;
import stormpot.Slot;
import testkits.SomeRandomException;
import testkits.SomeRandomThrowable;
import stormpot.Timeout;

import java.lang.ref.WeakReference;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.locks.Lock;
import java.util.concurrent.locks.LockSupport;
import java.util.concurrent.locks.ReentrantLock;

import static java.util.function.Function.identity;
import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;
import static testkits.AlloKit.$countDown;
import static testkits.AlloKit.$incrementAnd;
import static testkits.AlloKit.$new;
import static testkits.AlloKit.$null;
import static testkits.AlloKit.$observeNull;
import static testkits.AlloKit.$sleep;
import static testkits.AlloKit.$sync;
import static testkits.AlloKit.$throw;
import static testkits.AlloKit.CountingAllocator;
import static testkits.AlloKit.alloc;
import static testkits.AlloKit.allocator;
import static testkits.AlloKit.dealloc;
import static testkits.AlloKit.realloc;
import static testkits.AlloKit.reallocator;
import static testkits.ExpireKit.$age;
import static testkits.ExpireKit.$capture;
import static testkits.ExpireKit.$expired;
import static testkits.ExpireKit.$expiredIf;
import static testkits.ExpireKit.$explicitExpire;
import static testkits.ExpireKit.$fresh;
import static testkits.ExpireKit.$if;
import static testkits.ExpireKit.$poolable;
import static testkits.ExpireKit.$throwExpire;
import static testkits.ExpireKit.CountingExpiration;
import static testkits.ExpireKit.expire;
import static testkits.UnitKit.$delayedReleases;
import static testkits.UnitKit.claimRelease;
import static testkits.UnitKit.fork;
import static testkits.UnitKit.forkFuture;
import static testkits.UnitKit.join;
import static testkits.UnitKit.spinwait;
import static testkits.UnitKit.waitForThreadState;

/**
 * This is the generic test for Pool implementations. The test ensures that
 * an implementation adheres to the general contract of the Pool interface,
 * given certain circumstances and standardised configurations.
 * <p>
 * Pools may have other properties, and may be configurable to deviate from
 * the standardised behaviour. However, such properties must not be
 * observable within the standardised spectrum of configurations.
 * <p>
 * The tests for any special properties that a pool may have, must be put in
 * a pool-specific test case. Do not use assumptions or other tricks to
 * pollute this test case with tests for pool-specific or non-standard
 * behaviours and configurations.
 * <p>
 * The test case uses theories to apply to the set of possible Pool
 * implementations. Each implementation must have a PoolFixture, which is
 * used to construct and initialise the pool, based on a {@link PoolBuilder}.
 * <p>
 * <strong>Note:</strong> when adding, removing or modifying tests, also
 * remember to update the {@link Pool} javadoc - especially the part about
 * what promises are provided by the Pool interface and its implementations.
 * 
 * @author Chris Vest &lt;mr.chrisvest@gmail.com&gt;
 * @see Pool
 */
abstract class AllocatorBasedPoolTest extends AbstractPoolTest<GenericPoolable> {
  protected CountingAllocator allocator;
  protected PoolBuilder<GenericPoolable> builder;

  @BeforeEach
  void setUp() {
    allocator = allocator();
    builder = createInitialPoolBuilder(allocator).setSize(1);
  }

  protected abstract <T extends Poolable> PoolBuilder<T> createInitialPoolBuilder(Allocator<T> allocator);

  Pool<GenericPoolable> createPool() {
    pool = builder.build();
    threadSafeTap = pool.getThreadSafeTap();
    threadVirtualTap = pool.getVirtualThreadSafeTap();
    threadLocalTap = pool.getSingleThreadedTap();
    return pool;
  }

  @Override
  void createOneObjectPool() {
    createPool();
  }

  @Override
  void noBackgroundExpirationChecking() {
    if (builder.isBackgroundExpirationEnabled()) {
      builder.setBackgroundExpirationEnabled(false);
    }
  }

  void reducedBackgroundExpirationCheckDelay() {
    if (builder.isBackgroundExpirationEnabled()) {
      builder.setBackgroundExpirationCheckDelay(10);
    }
  }

  @Override
  void createPoolOfSize(int size) {
    builder.setSize(size);
    createPool();
  }
  
  /**
   * While the pool mustn't return null when we claim an object within the
   * timeout period, it likewise mustn't just come up with any random thing
   * that implements Poolable.
   * The objects have to come from the associated Allocator.
   * Or fixtures are required to count all allocations and deallocations,
   * so we can measure that our intended interactions do, in fact, reach
   * the Allocator. The PoolFixture will typically do this by wrapping the
   * source Allocator in a CountingAllocatorWrapper, but that is an
   * irrelevant detail.
   */
  @ParameterizedTest
  @EnumSource(Taps.class)
  void mustGetPooledObjectsFromAllocator(Taps taps) throws Exception {
    createPool();
    GenericPoolable obj = taps.get(this).claim(longTimeout);
    try {
      assertThat(allocator.countAllocations()).isGreaterThan(0);
    } finally {
      obj.release();
    }
  }
  
  /**
   * Same as {@link #mustReuseAllocatedObjects(Taps)}, but checks via the
   * allocator.
   */
  @ParameterizedTest
  @EnumSource(Taps.class)
  void mustReuseAllocatedObjectsAccordingToAllocator(Taps taps) throws Exception {
    createPool();
    PoolTap<GenericPoolable> tap = taps.get(this);
    tap.claim(longTimeout).release();
    tap.claim(longTimeout).release();
    assertThat(allocator.countAllocations()).isEqualTo(1);
  }
  
  /**
   * Be careful and prevent the creation of pools with a size less than zero.
   * @see PoolBuilder#setSize(long)
   */
  @Test
  void constructorMustThrowOnPoolSizeLessThanOne() {
    assertThrows(IllegalArgumentException.class, () -> builder.setSize(-1).build());
  }

  /**
   * We now permit pools to be built empty.
   * The contract of claim is to block indefinitely if one such pool were
   * to be created. Just like if they were permanently depleted.
   * It is expected that such pools will eventually have their target size
   * set to something greater than zero.
   */
  @Test
  void constructorMustAllowPoolSizeOfZero() throws Exception {
    Pool<GenericPoolable> pool = builder.setSize(0).build();
    try {
      assertNull(pool.claim(zeroTimeout));
    } finally {
      assertTrue(pool.shutdown().await(longTimeout));
    }
  }

  /**
   * Prevent the creation of pools with a null Expiration.
   * @see PoolBuilder#setExpiration(Expiration)
   */
  @Test
  void constructorMustThrowOnNullExpiration() {
    assertThrows(NullPointerException.class, () -> builder.setExpiration(null));
  }
  
  /**
   * Prevent the creation of pools with a null Allocator.
   * @see PoolBuilder#setAllocator(Allocator)
   */
  @Test
  void constructorMustThrowOnNullAllocator() {
    assertThrows(NullPointerException.class, () -> builder.setAllocator(null).build());
  }
  
  /**
   * Pools must use the provided expiration to determine whether slots
   * are invalid or not, instead of using their own ad-hoc mechanisms.
   * We test this by using an expiration that counts the number of times
   * it is invoked. Then we claim an object and assert that the expiration
   * was invoked at least once, presumably for that object.
   */
  @ParameterizedTest
  @EnumSource(Taps.class)
  void mustUseProvidedExpiration(Taps taps) throws Exception {
    builder.setAllocator(allocator());
    CountingExpiration expiration = expire($fresh);
    builder.setExpiration(expiration);
    createPool();
    PoolTap<GenericPoolable> tap = taps.get(this);
    tap.claim(longTimeout).release();
    assertThat(expiration.countExpirations()).isGreaterThanOrEqualTo(1);
  }
  
  /**
   * In the hopefully unlikely event that an Expiration throws an
   * exception, that exception should bubble out of the pool unspoiled.
   * <p>
   * We test for this by configuring an Expiration that always throws.
   * No guarantees are being made about when, exactly, it is that the pool will
   * invoke the Expiration. Therefore, we claim and release an object a
   * couple of times. That ought to do it.
   */
  @ParameterizedTest
  @EnumSource(Taps.class)
  void exceptionsFromExpirationMustBubbleOut(Taps taps) {
    builder.setExpiration(expire($throwExpire(new SomeRandomException())));
    createPool();
    PoolTap<GenericPoolable> tap = taps.get(this);

    PoolException e = assertThrows(PoolException.class, () -> {
      // make a couple of calls because pools might optimise for freshly
      // created objects
      tap.claim(longTimeout).release();
      tap.claim(longTimeout).release();
    });
    assertThat(e.getCause()).isInstanceOf(SomeRandomException.class);
  }
  
  /**
   * If the Expiration throws an exception when evaluating a slot, then that
   * slot should be considered invalid.
   * <p>
   * We test for this by configuring an expiration that always throws,
   * and then we make a claim and a release to make sure that it got invoked.
   * Then, since the pool size is one, we make another claim to make sure that
   * the invalid slot got reallocated. We don't care if that second claim
   * throws or not. All we're interested in, is whether the deallocation took
   * place.
   */
  @ParameterizedTest
  @EnumSource(Taps.class)
  void slotsThatMakeTheExpirationThrowAreInvalid(Taps taps) {
    builder.setExpiration(expire($throwExpire(new SomeRandomException())));
    createPool();
    PoolTap<GenericPoolable> tap = taps.get(this);
    PoolException e = assertThrows(PoolException.class, () -> tap.claim(longTimeout));
    assertThat(e.getCause()).isInstanceOf(SomeRandomException.class);
    // second call to claim to ensure that the deallocation has taken place
    e = assertThrows(PoolException.class, () -> tap.claim(longTimeout));
    assertThat(e.getCause()).isInstanceOf(SomeRandomException.class);
    // must have deallocated that object
    assertThat(allocator.countDeallocations()).isGreaterThanOrEqualTo(1);
  }
  
  /**
   * Expirations might require access to the actual object in question
   * being pooled, in order to implement advanced and/or domain specific
   * logic.
   * As with the claim count test above, we configure an expiration
   * that puts the value into an atomic. Then we assert that the value of the
   * atomic is one of the claimed objects.
   */
  @ParameterizedTest
  @EnumSource(Taps.class)
  void slotInfoMustHaveReferenceToItsPoolable(Taps taps) throws Exception {
    final AtomicReference<Poolable> lastPoolable = new AtomicReference<>();
    builder.setExpiration(expire($capture($poolable(lastPoolable), $fresh)));
    createPool();
    PoolTap<GenericPoolable> tap = taps.get(this);
    GenericPoolable a = tap.claim(longTimeout);
    a.release();
    GenericPoolable b = tap.claim(longTimeout);
    b.release();
    GenericPoolable poolable = (GenericPoolable) lastPoolable.get();
    assertThat(poolable).isIn(a, b);
  }
  
  /**
   * Verify that we can read back a stamp value from the SlotInfo that we have
   * set, when the Slot has not been re-allocated.
   * We test this with an Expiration that set the stamp value if it is zero,
   * or set a flag to signify that it has been remembered, if it is the value
   * we set it to. Then we claim and release a couple of times. If it works,
   * the second claim+release pair would have raised the flag.
   */
  @ParameterizedTest
  @EnumSource(Taps.class)
  void slotInfoMustRememberStamp(Taps taps) throws Exception {
    final AtomicBoolean rememberedStamp = new AtomicBoolean();
    Expiration<Poolable> expiration = info -> {
      long stamp = info.getStamp();
      if (stamp == 0) {
        info.setStamp(13);
      } else if (stamp == 13) {
        rememberedStamp.set(true);
      }
      return false;
    };
    builder.setExpiration(expiration);
    createPool();
    PoolTap<GenericPoolable> tap = taps.get(this);
    tap.claim(longTimeout).release(); // First set it...
    tap.claim(longTimeout).release(); // ... then get it.
    assertTrue(rememberedStamp.get());
  }
  
  /**
   * The SlotInfo stamp is zero by default. This must also be true of Slots
   * that has had their object reallocated. So if we set the stamp, then
   * reallocate the Poolable, then we should observe that the stamp is now back
   * to zero again.
   */
  @ParameterizedTest
  @EnumSource(Taps.class)
  void slotInfoStampMustResetIfSlotsAreReused(Taps taps) throws Exception {
    final AtomicLong zeroStampsCounted = new AtomicLong(0);
    Expiration<Poolable> expiration = info -> {
      long stamp = info.getStamp();
      info.setStamp(15);
      if (stamp == 0) {
        zeroStampsCounted.incrementAndGet();
        return false;
      }
      return true;
    };
    builder.setExpiration(expiration);
    noBackgroundExpirationChecking();
    createPool();
    PoolTap<GenericPoolable> tap = taps.get(this);

    tap.claim(longTimeout).release();
    tap.claim(longTimeout).release();
    tap.claim(longTimeout).release();
    
    assertThat(zeroStampsCounted.get()).isEqualTo(3L);
  }

  @ParameterizedTest
  @EnumSource(Taps.class)
  void slotInfoMustHaveAgeInMillis(Taps taps) throws InterruptedException {
    final AtomicLong age = new AtomicLong();
    builder.setExpiration(expire($capture($age(age), $fresh)));
    createPool();
    PoolTap<GenericPoolable> tap = taps.get(this);
    tap.claim(longTimeout).release();
    long firstAge = age.get();
    spinwait(5);
    tap.claim(longTimeout).release();
    long secondAge = age.get();
    assertThat(secondAge - firstAge).isGreaterThanOrEqualTo(5L);
  }

  @ParameterizedTest
  @EnumSource(Taps.class)
  void slotInfoAgeMustResetAfterAllocation(Taps taps) throws InterruptedException {
    final AtomicLong age = new AtomicLong();
    final AtomicLong createdNanoTime = new AtomicLong();
    builder.setExpiration(expire(
        $capture(info -> {
          age.set(info.getAgeMillis());
          long created = info.getCreatedNanoTime();
          createdNanoTime.set(created);
        }, $fresh)));
    noBackgroundExpirationChecking();
    // Reallocations will fail, causing the slot to be poisoned.
    // Then, the poisoned slot will not be reallocated again, but rather
    // go through the deallocate-allocate cycle.
    builder.setAllocator(reallocator(realloc($throw(new Exception("boom")))));
    createPool();
    PoolTap<GenericPoolable> tap = taps.get(this);
    tap.claim(longTimeout).release();
    Thread.sleep(100); // time transpires
    long firstCreatedNanoTime;
    GenericPoolable obj = tap.claim(longTimeout);
    try {
      long firstAge = age.get(); // age is now at least 5 ms
      firstCreatedNanoTime = createdNanoTime.get();
      assertThat(firstAge).isGreaterThanOrEqualTo(100);
      obj.expire();
    } finally {
      obj.release();
    }

    try {
      tap.claim(longTimeout).release();
    } catch (Exception e) {
      // ignore
    }

    // new object should have a new age
    tap.claim(longTimeout).release();
    long secondAge = age.get(); // age should be less than age of prev. obj.
    assertThat(TimeUnit.MILLISECONDS.toNanos(secondAge)).isLessThan(NanoClock.elapsed(firstCreatedNanoTime));
    long secondCreatedNanoTime = createdNanoTime.get();
    assertThat(secondCreatedNanoTime).isNotEqualTo(firstCreatedNanoTime);
  }

  @ParameterizedTest
  @EnumSource(Taps.class)
  void slotInfoAgeMustResetAfterReallocation(Taps taps) throws InterruptedException {
    final AtomicLong age = new AtomicLong();
    final AtomicLong createdNanoTime = new AtomicLong();
    builder.setExpiration(expire(
            $capture(info -> {
              age.set(info.getAgeMillis());
              long created = info.getCreatedNanoTime();
              createdNanoTime.set(created);
            }, $fresh)));
    noBackgroundExpirationChecking();
    createPool();
    PoolTap<GenericPoolable> tap = taps.get(this);
    tap.claim(longTimeout).release();
    Thread.sleep(100); // time transpires
    GenericPoolable obj = tap.claim(longTimeout);
    long firstCreatedNanoTime;
    try {
      long firstAge = age.get();
      firstCreatedNanoTime = createdNanoTime.get();
      assertThat(firstAge).isGreaterThanOrEqualTo(100);
      obj.expire(); // cause reallocation
    } finally {
      obj.release();
    }
    tap.claim(longTimeout).release(); // new object, new age
    long secondAge = age.get();
    assertThat(TimeUnit.MILLISECONDS.toNanos(secondAge)).isLessThan(NanoClock.elapsed(firstCreatedNanoTime));
    long secondCreatedNanoTime = createdNanoTime.get();
    assertThat(secondCreatedNanoTime).isNotEqualTo(firstCreatedNanoTime);
  }

  /**
   * Objects in the pool only live for a certain amount of time, and then
   * they must be replaced/renewed. Pools should generally try to renew
   * before the timeout elapses for the given object, but we don't test for
   * that here.
   * We set the TTL to be 1 millisecond, because that is short enough that
   * we can wait for it in a spin-loop. This way, the objects will always
   * appear to have expired when checked. This means that every claim will
   * always allocate a new object, and so our two claims will translate to
   * at least two allocations, which is what we check for. Note that the TTL
   * is so short that newly allocated objects might actually expire before a
   * claim call can complete, and thus more than the expected two allocations
   * are possible. This is why we check for *at least* two allocations.
   */
  @ParameterizedTest
  @EnumSource(Taps.class)
  void mustReplaceExpiredPoolables(Taps taps) throws Exception {
    builder.setExpiration(oneMsTTL);
    createPool();
    PoolTap<GenericPoolable> tap = taps.get(this);
    tap.claim(longTimeout).release();
    spinwait(2);
    tap.claim(longTimeout).release();
    assertThat(allocator.countAllocations()).isGreaterThanOrEqualTo(2);
  }
  
  /**
   * The size limit on a pool is strict, unless specially (as in a
   * non-standard way) configured otherwise. A pool is not allowed to 
   * have more objects allocated than the size, under any circumstances.
   * So, when the pool renews an object it must make ensure that the
   * deallocation of the old object happens-before the allocation of the
   * new object.
   * We test for this by configuring a pool with a timeout of one millisecond.
   * Then we claim an release an object, and wait for 2 milliseconds. Now the
   * object is expired, and must therefore be re-allocated before the next
   * claim can return. So we do a claim (but no release) and check that we have
   * had at least one deallocation. Note that our TTL is so short, that an
   * object might expire before a claim call can complete, so this is why we
   * check for *at least* one deallocation.
   * @see PoolBuilder#setSize(long)
   */
  @ParameterizedTest
  @EnumSource(Taps.class)
  void mustDeallocateExpiredPoolablesAndStayWithinSizeLimit(Taps taps) throws Exception {
    builder.setExpiration(oneMsTTL);
    createPool();
    PoolTap<GenericPoolable> tap = taps.get(this);
    tap.claim(longTimeout).release();
    spinwait(2);
    tap.claim(longTimeout).release();
    assertThat(allocator.countDeallocations()).isGreaterThanOrEqualTo(1);
  }
  
  /**
   * When we call shutdown() on a pool, the shutdown process is initiated and
   * the call returns a Completion object. A call to await() on this
   * Completion object will not return until the shutdown process has been
   * completed.
   * A shutdown process is not complete until all Poolables in the pool have
   * been deallocated. This means that any claimed objects must be released,
   * all the deallocations must have returned.
   * We test for this effect by making a pool of size 2 and claim both objects.
   * Then we release them. The order is important, to prevent the allocation
   * of just one object that is then reused. Then we shut the pool down and
   * wait for it to finish. After this, we must observe that exactly 2
   * deallocations have occurred.
   */
  @ParameterizedTest
  @EnumSource(Taps.class)
  void mustDeallocateAllPoolablesBeforeShutdownTaskReturns(Taps taps) throws Exception {
    builder.setSize(2);
    createPool();
    PoolTap<GenericPoolable> tap = taps.get(this);
    Poolable p1 = tap.claim(longTimeout);
    Poolable p2 = tap.claim(longTimeout);
    p1.release();
    p2.release();
    pool.shutdown().await(longTimeout);
    assertThat(allocator.countDeallocations()).isEqualTo(2);
  }
  
  /**
   * We have verified that the call to shutdown on a pool does not block on
   * claimed objects, and we have verified that all objects are deallocated
   * when the shut down completes. Now we need to verify that the release of
   * a claimed objects happens-before that object is deallocated as part of
   * the shut down process.
   * We test for this effect by claiming an object from a pool, never to
   * release it again. Then we initiate the shut down process. We await the
   * completion of the shut down process with a very short timeout, to be
   * sure that the process has actually started. This is to thwart any data
   * race that might otherwise be lurking. Then finally we assert that the
   * claimed object (the only one allocated) have not been deallocated.
   */
  @ParameterizedTest
  @EnumSource(Taps.class)
  void shutdownMustNotDeallocateClaimedPoolables(Taps taps) throws Exception {
    createPool();
    PoolTap<GenericPoolable> tap = taps.get(this);
    GenericPoolable obj = tap.claim(longTimeout);
    assertNotNull(obj, "Did not deplete pool in time");
    pool.shutdown().await(mediumTimeout);
    try {
      assertThat(allocator.countDeallocations()).isEqualTo(0);
    } finally {
      obj.release();
    }
  }

  @ParameterizedTest
  @EnumSource(Taps.class)
  void shutdownMustNotDeallocateTlrClaimedPoolables(Taps taps) throws Exception {
    createPool();
    PoolTap<GenericPoolable> tap = taps.get(this);
    tap.claim(longTimeout).release(); // Make next claim a TLR claim.
    GenericPoolable obj = tap.claim(longTimeout);
    assertNotNull(obj, "Did not deplete pool in time");
    pool.shutdown().await(mediumTimeout);
    try {
      assertThat(allocator.countDeallocations()).isEqualTo(0);
    } finally {
      obj.release();
    }
  }
  
  /**
   * Clients might hold on to objects after they have been released. This is
   * a user error, but pools must still maintain a coherent allocation and
   * deallocation pattern toward the Allocator.
   * We test this by configuring a pool with a short TTL so that the objects
   * will be deallocated as soon as possible. Then we claim an object, wait
   * the TTL out and release it numerous times. Then we claim another object
   * to guarantee that the deallocation of the first object have taken place
   * when we check the deallocation list for duplicates. The test pass if we
   * don't find any.
   */
  @ParameterizedTest
  @EnumSource(Taps.class)
  void mustNotDeallocateTheSameObjectMoreThanOnce(Taps taps) throws Exception {
    builder.setExpiration(oneMsTTL);
    createPool();
    PoolTap<GenericPoolable> tap = taps.get(this);
    Poolable obj = tap.claim(longTimeout);
    spinwait(2);
    obj.release();
    for (int i = 0; i < 10; i++) {
      try {
        obj.release();
      } catch (Exception ignore) {
        // we don't really care if the pool is able to detect this or not
        // we are still going to check with the Allocator.
      }
    }
    tap.claim(longTimeout).release();
    // check if the deallocation list contains duplicates
    List<GenericPoolable> deallocations = allocator.getDeallocations();
    synchronized (deallocations) {
      for (Poolable elm : deallocations) {
        assertThat(Collections.frequency(deallocations, elm)).as("Deallocations of %s", elm).isEqualTo(1);
      }
    }
  }
  
  /**
   * The shutdown procedure might be tempted to blindly iterate the pool
   * data structure and deallocate every possible slot. However, slots that
   * are empty should not be deallocated. In fact, the pool should never
   * try to deallocate any null value.
   * We attempt to test for this by having a special Allocator that flags
   * a boolean if a null was deallocated. Then we create a pool with the
   * Allocator and a negative TTL, and claim and release an object.
   * Then we shut the pool down. After the shut down procedure completes,
   * we check that no nulls were deallocated.
   */
  @ParameterizedTest
  @EnumSource(Taps.class)
  void shutdownMustNotDeallocateEmptySlots(Taps taps) throws Exception {
    final AtomicBoolean wasNull = new AtomicBoolean();
    allocator = allocator(dealloc($observeNull(wasNull, $null)));
    builder.setAllocator(allocator).setExpiration(oneMsTTL);
    createPool();
    PoolTap<GenericPoolable> tap = taps.get(this);
    tap.claim(longTimeout).release();
    pool.shutdown().await(longTimeout);
    assertFalse(wasNull.get());
  }

  @ParameterizedTest
  @EnumSource(Taps.class)
  void shutdownMustEventuallyDeallocateAllPoolables(Taps taps) throws Exception {
    int size = 10;
    builder.setSize(size);
    createPool();
    PoolTap<GenericPoolable> tap = taps.get(this);
    List<GenericPoolable> objs = new ArrayList<>();
    for (int i = 0; i < size; i++) {
      objs.add(tap.claim(longTimeout));
    }
    Completion completion = pool.shutdown();
    completion.await(shortTimeout);
    for (GenericPoolable obj : objs) {
      obj.release();
    }
    completion.await(longTimeout);
    assertThat(allocator.countDeallocations()).isEqualTo(size);
  }
  
  /**
   * Pools must be prepared in the event that an Allocator throws an
   * Exception from allocate. If it is not possible to allocate an object,
   * then the pool must throw a PoolException through claim.
   * @see PoolException
   */
  @ParameterizedTest
  @EnumSource(Taps.class)
  void mustPropagateExceptionsFromAllocateThroughClaim(Taps taps) throws Exception {
    final RuntimeException expectedException = new RuntimeException("boo");
    allocator = allocator(alloc($throw(expectedException)));
    builder.setAllocator(allocator);
    createPool();
    PoolTap<GenericPoolable> tap = taps.get(this);
    try {
      tap.claim(longTimeout);
      fail("expected claim to throw");
    } catch (PoolException poolException) {
      assertThat(poolException.getCause()).isSameAs(expectedException);
    }
  }

  /**
   * Pools must be prepared in the event that a Reallocator throws an
   * Exception from reallocate. If it is not possible to reallocate an object,
   * then the pool must throw a PoolException through claim.
   * @see PoolException
   */
  @ParameterizedTest
  @EnumSource(Taps.class)
  void mustPropagateExceptionsFromReallocateThroughClaim(Taps taps) throws Exception {
    final RuntimeException expectedException = new RuntimeException("boo");
    AtomicBoolean hasExpired = new AtomicBoolean();
    builder.setAllocator(reallocator(realloc($throw(expectedException))));
    builder.setExpiration(expire($expiredIf(hasExpired)));
    builder.setSize(2);
    createPool();
    PoolTap<GenericPoolable> tap = taps.get(this);
    // Make sure the pool is fully allocated
    hasExpired.set(false);
    GenericPoolable obj1 = tap.claim(longTimeout);
    GenericPoolable obj2 = tap.claim(longTimeout);
    obj1.release();
    obj2.release();
    // Now consider it expired, send it back for reallocation,
    // the reallocation throws and poisons, and the poison gets thrown.
    // The pool will race to reallocate the poisoned objects, but it won't
    // win the race, because there are two slots circulating in the pool,
    // so it should be highly probable that we are able to grab one of them
    // while the other one is being reallocated.
    hasExpired.set(true);
    try {
      tap.claim(longTimeout);
      fail("expected claim to throw");
    } catch (PoolException poolException) {
      assertThat(poolException.getCause()).isSameAs(expectedException);
    }
  }
  
  /**
   * A pool must not break its internal invariants if an Allocator throws an
   * exception in allocate, and it must still be usable after the exception
   * has bubbled out.
   * We test this by configuring an Allocator that throws an exception from
   * the first allocation, and then allocates as normal if not. On the first
   * call to claim, we catch the exception that propagates out of the pool
   * and flip the boolean. Then the next call to claim must return a non-null
   * object within the test timeout.
   * If it does not, then the pool might have broken locks or it might have
   * garbage in the slot location.
   * The first claim might race with eager reallocation, though. So we don't
   * assert on the exception, and make sure to release any object we might
   * get back.
   */
  @ParameterizedTest
  @EnumSource(Taps.class)
  void mustStillBeUsableAfterExceptionInAllocate(Taps taps) throws Exception {
    allocator = allocator(alloc($throw(new RuntimeException("boo")), $new));
    builder.setAllocator(allocator);
    createPool();
    PoolTap<GenericPoolable> tap = taps.get(this);
    try {
      tap.claim(longTimeout).release();
    } catch (PoolException ignore) {}
    GenericPoolable obj = pool.claim(longTimeout);
    assertThat(obj).isNotNull();
    obj.release();
  }

  /**
   * Likewise as in {@link #mustStillBeUsableAfterExceptionInAllocate}, a pool
   * must not break its internal invariants if a Reallocator throws an exception
   * in reallocate, and it must still be usable after the exception has bubbled out.
   * We test for this by configuring a Reallocator that always throws on
   * reallocate, and we use explicit expiration to mark the first slot as expired.
   * Then, when we call claim, that first live slot will be sent back for
   * reallocation, which will throw and poison the slot.
   * Then the slot comes back to our still on-going claim, which throws
   * because of the poison. The slot then gets sent back again, and now,
   * because of the poison, it will not be reallocated, but instead have a
   * fresh Poolable allocated anew. This new good Poolable is what we get out
   * of the last call to claim.
   */
  @ParameterizedTest
  @EnumSource(Taps.class)
  void mustStillBeUsableAfterExceptionInReallocate(Taps taps) throws Exception {
    AlloKit.CountingReallocator alloc = reallocator(
            alloc($new),
            realloc($throw(new RuntimeException("boo from realloc"))));
    builder.setAllocator(alloc);
    builder.setExpiration(Expiration.never());
    noBackgroundExpirationChecking();
    createPool();
    PoolTap<GenericPoolable> tap = taps.get(this);
    GenericPoolable obj = tap.claim(longTimeout); // object now allocated
    obj.expire();
    obj.release();
    try {
      tap.claim(longTimeout).release();
      // if "claim" doesn't throw, then the background thread might have cleaned up the poisoned
      // slot before we could get to it. In that case, the allocation count should be 2.
      assertThat(alloc.countAllocations()).isEqualTo(2);
    } catch (PoolException ignore) {}
    GenericPoolable claim = tap.claim(longTimeout);
    assertThat(claim).isNotNull();
    claim.release();
  }
  
  /**
   * We cannot guarantee that exceptions from deallocating objects will
   * propagate out through release, because the actual deallocation might be
   * done asynchronously in a different thread.
   * So instead, we are going to guarantee the opposite: that calling release
   * on an object will never propagate, or even throw, any exceptions ever.
   * Users of a pool who are interested in logging what exceptions might be
   * thrown by their allocators deallocate method, are going to have to wrap
   * their allocators in try-catching and logging code.
   * We test this by configuring the pool with an Allocator that always throws
   * on deallocate, and a very short TTL. Then we claim and release an object,
   * spin the TTL out and then claim another one. This ensures that the
   * deallocation actually takes place, because full pools guarantee that
   * the deallocation of an expired object happens before the allocation
   * of its replacement.
   */
  @ParameterizedTest
  @EnumSource(Taps.class)
  void mustSwallowExceptionsFromDeallocateThroughRelease(Taps taps) throws Exception {
    allocator = allocator(dealloc($throw(new RuntimeException("boo"))));
    builder.setAllocator(allocator);
    builder.setExpiration(oneMsTTL);
    createPool();
    PoolTap<GenericPoolable> tap = taps.get(this);
    tap.claim(longTimeout).release();
    spinwait(2);
    tap.claim(longTimeout).release();
  }
  
  /**
   * While it is technically possible to propagate exceptions from an
   * Allocators deallocate method during the shutdown procedure, it would not
   * be a desirable behaviour because it would be inconsistent with how this
   * works for the release method on Poolable - and Slot, for that matter.
   * People who are interested in the exceptions that deallocate might throw,
   * should wrap their Allocators in implementations that log them. If they
   * do this, then they will already have a means for accessing the exceptions
   * thrown. As such, there is no point in also logging the exceptions in the
   * shut down procedure.
   * We test this by configuring a pool with an Allocator that always throws
   * on deallocate, in addition to counting deallocations. We also keep the
   * standard TTL configuration to prevent the objects from being immediately
   * deallocated when they are released, and we set the size to 2. Then we
   * claim two objects and then release them. This means that two objects are
   * now live in the pool. Then we shut the pool down.
   * The test passes if the shut down procedure completes without throwing
   * any exceptions, and we observe exactly 2 deallocations.
   */
  @ParameterizedTest
  @EnumSource(Taps.class)
  void mustSwallowExceptionsFromDeallocateThroughShutdown(Taps taps) throws Exception {
    CountingAllocator allocator = allocator(
        dealloc($throw(new RuntimeException("boo"))));
    builder.setAllocator(allocator).setSize(2);
    createPool();
    PoolTap<GenericPoolable> tap = taps.get(this);
    Poolable obj = tap.claim(longTimeout);
    tap.claim(longTimeout).release();
    obj.release();
    pool.shutdown().await(longTimeout);
    assertThat(allocator.countDeallocations()).isEqualTo(2);
  }
  
  /**
   * Allocators must never return <code>null</code>, and if they do, then a
   * call to claim must throw a PoolException to indicate this fact.
   * We test this by configuring the pool with an Allocator that always
   * returns null from allocate, and then we try to claim from this pool.
   * This call to claim must then throw a PoolException.
   * @see Allocator#allocate(Slot)
   * @see PoolException
   */
  @ParameterizedTest
  @EnumSource(Taps.class)
  void claimMustThrowIfAllocationReturnsNull(Taps taps) {
    Allocator<GenericPoolable> allocator = allocator(alloc($null));
    builder = builder.setAllocator(allocator);
    createPool();
    PoolTap<GenericPoolable> tap = taps.get(this);
    assertThrows(PoolException.class, () -> tap.claim(longTimeout));
  }

  /**
   * Reallocators must never return <code>null</code> from
   * {@link Reallocator#reallocate(Slot, Poolable)}, and if they do, then a
   * call to claim must throw a PoolException to indicate this fact.
   * We test this by configuring a Reallocator that always return null on
   * reallocation, and an Expiration that will mark the first Poolable we try
   * to claim, as expired. Then we call claim, and object will be allocated,
   * it will be considered expired and sent back, then it gets reallocated
   * and the reallocate method returns null. Now the slot is poisoned, gets
   * back to the still on-going call to claim, that now throws a
   * PoolException.
   * @see Allocator#allocate(Slot)
   * @see Reallocator#reallocate(Slot, Poolable)
   * @see PoolException
   */
  @ParameterizedTest
  @EnumSource(Taps.class)
  void claimMustThrowIfReallocationReturnsNull(Taps taps) throws Exception {
    allocator = reallocator(realloc($null));
    AtomicBoolean hasExpired = new AtomicBoolean();
    builder.setAllocator(allocator);
    builder.setExpiration(expire($expiredIf(hasExpired)));
    builder.setSize(2);
    createPool();
    PoolTap<GenericPoolable> tap = taps.get(this);
    // Make sure the pool is fully allocated
    hasExpired.set(false);
    GenericPoolable obj1 = tap.claim(longTimeout);
    GenericPoolable obj2 = tap.claim(longTimeout);
    obj1.release();
    obj2.release();
    // Now consider it expired. This will send it back for reallocation,
    // the reallocation turns it into null, which gets thrown as poison.
    // The pool will race to reallocate the poisoned objects, but it won't
    // win the race, because there are two slots circulating in the pool,
    // so it should be highly probable that we are able to grab one of them
    // while the other one is being reallocated.
    hasExpired.set(true);
    assertThrows(PoolException.class, () -> tap.claim(longTimeout));
  }
  
  /**
   * Claim with timeout must adhere to its timeout value. Some pool
   * implementations do the waiting in a loop, and if they don't do it right,
   * they might end up resetting the timeout every time they loop. This test
   * tries to ensure that that no such resetting can happen because an object
   * is released back into the pool. This may not cover all cases that are
   * possible with the different pool implementations, but it is at least a
   * start. And one that can be generally tested for across pool
   * implementations. Chances are, that if a pool handles this specific case,
   * then it handles all cases that are relevant to its implementation.
   * <p>
   * We test for this by depleting a big pool with a very short TTL. After the
   * pool has been depleted, the allocator will no longer create any more
   * objects, so no expired objects will be renewed. Then we try to claim one
   * more object, and that will block. Meanwhile, another thread will perform
   * a trickle of releases. No useful objects will come back to the pool, but
   * there will still be some amount of activity. While all this goes on, the
   * blocked claim call must adhere to its specified timeout.
   * try to claim one more object
   */
  @ParameterizedTest
  @EnumSource(Taps.class)
  void claimMustStayWithinTimeoutEvenIfExpiredObjectIsReleased(Taps taps) throws Exception {
    // NOTE: This test is a little slow and may hit the 42424 ms timeout even
    // if it was actually supposed to pass. Try running it again if there are
    // any problems. I may have to revisit this one in the future.
    final Poolable[] objs = new Poolable[50];
    final Lock lock = new ReentrantLock();
    allocator = allocator(alloc($sync(lock, $new)));
    builder.setAllocator(allocator);
    builder.setExpiration(fiveMsTTL);
    builder.setSize(objs.length);
    createPool();
    PoolTap<GenericPoolable> tap = taps.get(this);
    for (int i = 0; i < objs.length; i++) {
      objs[i] = tap.claim(longTimeout);
      assertNotNull(objs[i], "Did not claim an object in time");
    }
    lock.lock(); // prevent new allocations
    try {
      Thread thread = fork($delayedReleases(10, TimeUnit.MILLISECONDS, objs));
      try {
        // must return before test times out:
        GenericPoolable obj = tap.claim(new Timeout(50, TimeUnit.MILLISECONDS));
        if (obj != null) {
          obj.release();
        }
      } finally {
        thread.interrupt();
        thread.join();
      }
    } finally {
      lock.unlock();
    }
  }
  
  /**
   * Even if the Allocator has never made a successful allocation, the pool
   * must still be able to complete its shut-down procedure.
   * In this case we test with an allocator that always returns null.
   */
  @Test
  void mustCompleteShutDownEvenIfAllSlotsHaveNullErrors() throws Exception {
    Allocator<GenericPoolable> allocator = allocator(alloc($null));
    Pool<GenericPoolable> pool = givenPoolWithFailedAllocation(allocator);
    // the shut-down procedure must complete before the test times out.
    pool.shutdown().await(longTimeout);
  }

  private Pool<GenericPoolable> givenPoolWithFailedAllocation(
      Allocator<GenericPoolable> allocator) {
    builder.setAllocator(allocator);
    createPool();
    try {
      // ensure at least one allocation attempt has taken place
      pool.claim(longTimeout);
      fail("allocation attempt should have failed!");
    } catch (Exception ignore) {
      // we don't care about this one
    }
    return pool;
  }
  
  /**
   * As with
   * {@link #mustCompleteShutDownEvenIfAllSlotsHaveNullErrors()},
   * the pool must be able to shut down if it has never been able to allocate
   * anything.
   * In this case we test with an allocator that always throws an exception.
   */
  @Test
  void mustCompleteShutDownEvenIfAllSlotsHaveAllocationErrors() throws Exception {
    Allocator<GenericPoolable> allocator =
        allocator(alloc($throw(new Exception("it's terrible stuff!!!"))));
    Pool<GenericPoolable> pool =
        givenPoolWithFailedAllocation(allocator);
    // must complete before the test timeout:
    pool.shutdown().await(longTimeout);
  }

  @Test
  void mustBeAbleToShutDownWhenAllocateAlwaysThrows() throws Exception {
    AtomicLong counter = new AtomicLong();
    allocator = allocator(alloc(
        $incrementAnd(counter, $throw(new RuntimeException("boo")))));
    builder.setAllocator(allocator);
    builder.setSize(3);
    createPool();
    //noinspection StatementWithEmptyBody
    while (counter.get() < 2) {
      // do nothing
    }
    pool.shutdown().await(longTimeout);
  }

  /**
   * Here's the scenario we're trying to target:
   * <p>
   * <ul>
   * <li>You (or your thread) do a successful claim, and triumphantly stores it
   *   in the ThreadLocal cache.</li>
   * <li>You then return the object after use, so now it's back in the
   *   live-queue for others to grab.</li>
   * <li>Someone else claims the object, and explicitly expires the object.</li>
   * <li>You want to claim an object again, and start by looking in the
   *   ThreadLocal cache.</li>
   * <li>You find the slot for the object you had last, but the slot is poisoned
   *   with the explicit expiration.</li>
   * <li>Now, because you found it in the ThreadLocal cache – and notably did
   *   *not* pull it off of the live-queue – you cannot just put it on the
   *   dead-queue, because that could lead to unbounded memory use.</li>
   * <li>Instead, it has to be marked as live, and we instead have to wait for
   *   someone to pull it off of the live-queue, check the poison again, and
   *   *then* put it on the dead-queue.</li>
   * <li>The slot will then be sent to the allocator for reallocation.</li>
   * <li>The returned object should then be different from the initial one.</li>
   * </ul>
   */
  @ParameterizedTest
  @EnumSource(Taps.class)
  void mustNotCacheExplicitlyExpiredSlots(Taps taps) throws Exception {
    builder.setSize(1);
    builder.setExpiration(Expiration.never());
    noBackgroundExpirationChecking();

    createPool();
    PoolTap<GenericPoolable> tap = taps.get(this);

    // Prime any thread-local cache
    GenericPoolable initial = tap.claim(longTimeout);
    initial.release(); // Places slot at end of queue

    forkFuture(() -> {
      GenericPoolable obj = tap.claim(longTimeout);
      obj.expire();
      obj.release();
      return null;
    }).get();

    GenericPoolable second = tap.claim(longTimeout);
    try {
      assertThat(second).isNotSameAs(initial);
    } finally {
      second.release();
    }
  }

  @Test
  void targetSizeMustBeGreaterThanOrEqualToZero() {
    createPool();
    assertThrows(IllegalArgumentException.class, () -> pool.setTargetSize(-1));
  }

  @SuppressWarnings("BusyWait")
  @Test
  void targetSizeOfZeroMustBeAllowed() throws Exception {
    createPool();
    pool.claim(longTimeout).release();
    pool.setTargetSize(0);
    while (allocator.countAllocations() > allocator.countDeallocations()) {
      Thread.sleep(1);
    }
  }

  @Test
  void getTargetSizeMustReturnLastSetTargetSize() {
    createPool();
    pool.setTargetSize(3);
    assertThat(pool.getTargetSize()).isEqualTo(3);
  }

  /**
   * When we increase the size of a depleted pool, it should be possible to
   * make claim again and get out newly allocated objects.
   * <p>
   * We test for this by depleting a pool, upping the size and then claiming
   * again with a timeout that is longer than the timeout of the test. The test
   * pass if it does not time out.
   */
  @ParameterizedTest
  @EnumSource(Taps.class)
  void increasingSizeMustAllowMoreAllocations(Taps taps) throws Exception {
    reducedBackgroundExpirationCheckDelay();
    createPool();
    PoolTap<GenericPoolable> tap = taps.get(this);
    GenericPoolable a = tap.claim(longTimeout); // depleted
    pool.setTargetSize(2);
    // now this mustn't block:
    GenericPoolable b = tap.claim(longTimeout);
    a.release();
    b.release();
  }

  /**
   * We must somehow ensure that the pool starts deallocating more than it
   * allocates, when the pool is shrunk. This is difficult because the pool
   * cannot tell us when it reaches the target size, so we have to figure this
   * out by using a special allocator.
   * <p>
   * We test for this by configuring a CountingAllocator that also unparks a
   * thread (namely ours, the main thread for the test) at every allocation
   * and deallocation. We also configure the pool to have a somewhat large
   * initial size, so we can shrink it later. Then we deplete the pool, and
   * set a smaller target size. After setting the new target size, we release
   * just enough objects for the pool to reach it, and then we wait for the
   * allocator to register that same number of deallocations. This has to
   * happen before the test times out. After that, we check that the difference
   * between the allocations and the deallocations matches the new target size.
   */
  @ParameterizedTest
  @EnumSource(Taps.class)
  void decreasingSizeMustEventuallyDeallocateSurplusObjects(Taps taps) throws Exception {
    int startingSize = 5;
    int newSize = 1;
    allocator = allocator();
    builder.setSize(startingSize).setAllocator(allocator);
    reducedBackgroundExpirationCheckDelay();
    createPool();
    PoolTap<GenericPoolable> tap = taps.get(this);
    List<GenericPoolable> objs = new ArrayList<>();

    while (allocator.countAllocations() != startingSize) {
      objs.add(tap.claim(longTimeout)); // force the pool to do work
    }
    pool.setTargetSize(newSize);
    while (allocator.countDeallocations() != startingSize - newSize) {
      if (!objs.isEmpty()) {
        objs.removeFirst().release(); // give the pool objects to deallocate
      } else {
        tap.claim(longTimeout).release(); // prod it & poke it
      }
      LockSupport.parkNanos(10000000); // 10 millis
    }
    int actualSize =
        allocator.countAllocations() - allocator.countDeallocations();
    try {
      assertThat(actualSize).isEqualTo(newSize);
    } finally {
      for (GenericPoolable obj : objs) {
        obj.release();
      }
    }
  }

  /**
   * Similar to the decreasingSizeMustEventuallyDeallocateSurplusObjects test
   * above, but this time the objects are all expired after the pool has been
   * shrunk.
   * <p>
   * Again, we deplete the pool. Once depleted, our expiration has been
   * configured such, that all subsequent items one tries to claim, will be
   * expired.
   * <p>
   * Then we set the new lower target size, and release just enough for the
   * pool to reach the new target.
   * <p>
   * Then we try to claim an object from the pool with a very short timeout.
   * This will return null because the pool is still depleted. We also check
   * that the pool has not made any new allocations, even though we have been
   * releasing objects. We don't check the deallocations because it's
   * complicated and we did it in the
   * decreasingSizeMustEventuallyDeallocateSurplusObjects test above.
   */
  @Test
  void mustNotReallocateWhenReleasingExpiredObjectsIntoShrunkPool()
      throws Exception {
    int startingSize = 5;
    int newSize = 1;
    Expiration<GenericPoolable> expiration = expire(
        // our 5 items are not expired when we deplete the pool
        $fresh, $fresh, $fresh, $fresh, $fresh,
        // but all items we try to claim after that *are* expired.
        $expired
    );
    builder.setExpiration(expiration).setAllocator(allocator);
    builder.setSize(startingSize);
    noBackgroundExpirationChecking();
    createPool();
    List<GenericPoolable> objs = new ArrayList<>();
    for (int i = 0; i < startingSize; i++) {
      objs.add(pool.claim(longTimeout));
    }
    assertThat(objs.size()).isEqualTo(startingSize);
    pool.setTargetSize(newSize);
    for (int i = 0; i < startingSize - newSize; i++) {
      // release the surplus expired objects back into the pool
      objs.removeFirst().release();
    }
    // now the released objects should not cause reallocations, so claim
    // returns null (it's still depleted) and allocation count stays put
    try {
      assertThat(pool.claim(shortTimeout)).isNull();
      assertThat(allocator.countAllocations()).isEqualTo(startingSize);
    } finally {
      objs.removeFirst().release();
    }
  }

  @Test
  void settingTargetSizeOnPoolThatHasBeenShutDownDoesNothing() {
    builder.setSize(3);
    createPool();
    pool.shutdown();
    pool.setTargetSize(10); // this should do nothing, because it's shut down
    assertThat(pool.getTargetSize()).isEqualTo(3);
  }

  /**
   * Make sure that the pool does not get into a bad state, caused by concurrent
   * background resizing jobs interfering with each other.
   * <p>
   * We test this by creating a small pool, then resizing it larger (so much so that
   * any resizing job is unlikely to finish before we can make our next move) and then
   * immediately resizing it smaller again. This should put multiple resizing jobs in
   * flight. When all the background jobs complete, we should observe that the pool
   * ended up with exactly the target size number of items in it.
   */
  @ParameterizedTest
  @EnumSource(Taps.class)
  void increasingAndDecreasingSizeInQuickSuccessionMustEventuallyReachTargetSize(Taps taps)
      throws Exception {
    createPool();
    PoolTap<GenericPoolable> tap = taps.get(this);

    // Fiddle with the target size.
    pool.setTargetSize(20);
    pool.setTargetSize(1);

    // Then wait for the size of the pool to settle
    int deallocations;
    int allocations;
    do {
      Thread.sleep(1);
      deallocations = allocator.countDeallocations();
      allocations = allocator.countAllocations();
    } while (allocations - deallocations > 1);

    // Now we should be left with exactly one object that we can claim:
    GenericPoolable obj = tap.claim(longTimeout);
    try {
      assertThat(tap.claim(shortTimeout)).isNull();
    } finally {
      obj.release();
    }
  }

  @ParameterizedTest
  @EnumSource(Taps.class)
  void decreasingSizeMustNotDeallocateTlrClaimedObjects(Taps taps) throws Exception {
    createPoolOfSize(2);
    PoolTap<GenericPoolable> tap = taps.get(this);

    while (allocator.countAllocations() < 2) {
      Thread.onSpinWait();
    }

    tap.claim(longTimeout).release(); // Primed for TLR claim.
    GenericPoolable obj = tap.claim(longTimeout); // The TLR claim.
    pool.setTargetSize(1);

    while (allocator.countDeallocations() < 1) {
      Thread.onSpinWait();
    }

    obj.release();
    List<GenericPoolable> deallocations = allocator.getDeallocations();
    synchronized (deallocations) {
      assertThat(deallocations).doesNotContain(obj);
    }
  }

  /**
   * The specification only promises to correctly handle Expirations that throw
   * Exceptions, but we also test with Throwable, just in case we might be able
   * to recover from them as well.
   */
  @ParameterizedTest
  @EnumSource(Taps.class)
  void mustNotLeakSlotsIfExpirationThrowsThrowableInsteadOfException(Taps taps)
      throws InterruptedException {
    final AtomicBoolean shouldThrow = new AtomicBoolean(true);
    builder.setExpiration(expire(
        $if(shouldThrow,
            $throwExpire(new SomeRandomThrowable("Boom!")),
            $fresh)));
    noBackgroundExpirationChecking();

    createPool();
    PoolTap<GenericPoolable> tap = taps.get(this);
    try {
      tap.claim(longTimeout);
      fail("Expected claim to throw");
    } catch (PoolException pe) {
      assertThat(pe.getCause()).isInstanceOf(SomeRandomThrowable.class);
    }

    // Now, the slot should not have leaked, so the next claim should succeed:
    shouldThrow.set(false);
    tap.claim(longTimeout).release();
    pool.shutdown();
  }

  /**
   * If the interrupt happen inside the Allocator.allocate(Slot) method, and
   * then the allocator either clears the interruption status, or throws an
   * InterruptedException which in turn will be understood as poison, then the
   * allocation thread can miss the shutdown signal, and never begin the
   * shutdown sequence.
   */
  @Test
  void mustCompleteShutdownEvenIfAllocatorEatsTheInterruptSignal() throws Exception {
    builder.setAllocator(reallocator(
        alloc($sleep(1000, $new)),
        realloc($sleep(1000, $new))));
    // Give the allocation thread a head-start to get stuck sleeping in the
    // Allocator.allocate method:
    createPool();
    GenericPoolable obj = pool.claim(mediumTimeout);
    if (obj != null) {
      obj.release();
    }
    // The interrupt signal from shutdown will get caught by the Allocator:
    pool.shutdown().await(longTimeout);
  }

  @ParameterizedTest
  @EnumSource(Taps.class)
  void poolMustTolerateInterruptedExceptionFromAllocatorWhenNotShutDown(Taps taps)
      throws InterruptedException {
    builder.setAllocator(
        allocator(alloc($throw(new InterruptedException("boom")), $new)));
    AtomicBoolean hasExpired = new AtomicBoolean();
    builder.setExpiration(expire($expiredIf(hasExpired)));
    createPool();
    PoolTap<GenericPoolable> tap = taps.get(this);

    // This will capture the failed allocation:
    try {
      tap.claim(longTimeout).release();
    } catch (PoolException e) {
      assertThat(e.getCause()).isInstanceOf(InterruptedException.class);
    }

    // This should succeed like nothing happened:
    tap.claim(longTimeout).release();

    // Cause an extra reallocation to make sure that works:
    hasExpired.set(true);
    assertNull(tap.claim(shortTimeout));
    hasExpired.set(false);

    // These should again succeed like nothing happened:
    tap.claim(longTimeout).release();
    tap.claim(longTimeout).release();
    shutPoolDown();
  }

  @Test
  void managedPoolMustCountLifetimeAllocations() throws InterruptedException {
    ManagedPool managedPool = createPool().getManagedPool();

    for (int i = 0; i < 100; i++) {
      GenericPoolable obj = pool.claim(longTimeout);
      obj.getSlot().expire(obj);
      obj.release();
    }

    // We use "greater than 90" to allow some slack for a lazily updated
    // counter.
    assertThat(managedPool.getAllocationCount()).isGreaterThan(90L);
  }

  @Test
  void managedPoolMustCountAllocationsFailingWithExceptions() throws Exception {
    CountDownLatch latch = new CountDownLatch(1);
    Exception exception = new Exception("boo");
    int size = 2;
    builder.setSize(size).setAllocator(allocator(alloc(
        $new,
        $throw(exception),
        $throw(exception),
        $countDown(latch, $new))));
    ManagedPool managedPool = createPool().getManagedPool();

    // Claim and release all objects to force the allocations.
    var objs = new ArrayList<GenericPoolable>();
    while (objs.size() < size) {
      try {
        objs.add(pool.claim(longTimeout));
      } catch (PoolException ignore) {
      }
    }
    objs.forEach(GenericPoolable::release);

    assertThat(managedPool.getFailedAllocationCount()).isEqualTo(2L);
  }

  @Test
  void managedPoolMustCountAllocationsFailingByReturningNull() throws Exception {
    builder.setSize(2).setAllocator(
        allocator(alloc($new, $null, $null, $new)));
    ManagedPool managedPool = createPool().getManagedPool();
    GenericPoolable a = null;
    GenericPoolable b = null;

    // we have to loop both claims because proactive reallocation can move the
    // poisoned slots around inside the pool.
    do {
      try {
        a = pool.claim(longTimeout);
      } catch (PoolException ignore) {}
    } while (a == null);
    do {
      try {
        b = pool.claim(longTimeout);
      } catch (PoolException ignore) {}
    } while (b == null);

    a.release();
    b.release();

    assertThat(managedPool.getFailedAllocationCount()).isEqualTo(2L);
  }

  @Test
  void managedPoolMustCountReallocationsFailingWithExceptions() throws Exception {
    builder.setSize(1);
    Exception exception = new Exception("boo");
    builder.setAllocator(reallocator(realloc($throw(exception), $new)));
    builder.setExpiration(expire($expired, $fresh));
    ManagedPool managedPool = createPool().getManagedPool();

    GenericPoolable obj = null;
    do {
      try {
        obj = pool.claim(longTimeout);
      } catch (PoolException ignore) {}
    } while (obj == null);
    obj.release();

    assertThat(managedPool.getFailedAllocationCount()).isEqualTo(1L);
  }

  @Test
  void managedPoolMustCountReallocationsFailingByReturningNull() throws Exception {
    builder.setSize(1);
    builder.setAllocator(reallocator(realloc($null, $new)));
    builder.setExpiration(expire($expired, $fresh));
    ManagedPool managedPool = createPool().getManagedPool();

    GenericPoolable obj = null;
    do {
      try {
        obj = pool.claim(longTimeout);
      } catch (PoolException ignore) {}
    } while (obj == null);
    obj.release();

    assertThat(managedPool.getFailedAllocationCount()).isEqualTo(1L);
  }

  @Test
  void managedPoolMustAllowGettingAndSettingPoolTargetSize() {
    builder.setSize(2);
    ManagedPool managedPool = createPool().getManagedPool();
    assertThat(managedPool.getTargetSize()).isEqualTo(2);
    managedPool.setTargetSize(5);
    assertThat(managedPool.getTargetSize()).isEqualTo(5);
  }

  @Test
  void managedPoolMustReturnNaNWhenNoMetricsRecorderHasBeenConfigured() {
    ManagedPool managedPool = createPool().getManagedPool();
    assertThat(managedPool.getAllocationLatencyPercentile(0.5)).isNaN();
    assertThat(managedPool.getObjectLifetimePercentile(0.5)).isNaN();
    assertThat(managedPool.getAllocationFailureLatencyPercentile(0.5)).isNaN();
    assertThat(managedPool.getReallocationLatencyPercentile(0.5)).isNaN();
    assertThat(managedPool.getReallocationFailureLatencyPercentile(0.5)).isNaN();
    assertThat(managedPool.getDeallocationLatencyPercentile(0.5)).isNaN();
  }

  @Test
  void managedPoolMustGetLatencyPercentilesFromConfiguredMetricsRecorder() {
    builder.setMetricsRecorder(
        new FixedMeanMetricsRecorder(1.37, 2.37, 3.37, 4.37, 5.37, 6.37));
    ManagedPool managedPool = createPool().getManagedPool();
    assertThat(managedPool.getObjectLifetimePercentile(0.5)).isEqualTo(1.37);
    assertThat(managedPool.getAllocationLatencyPercentile(0.5)).isEqualTo(2.37);
    assertThat(managedPool.getAllocationFailureLatencyPercentile(0.5)).isEqualTo(3.37);
    assertThat(managedPool.getReallocationLatencyPercentile(0.5)).isEqualTo(4.37);
    assertThat(managedPool.getReallocationFailureLatencyPercentile(0.5)).isEqualTo(5.37);
    assertThat(managedPool.getDeallocationLatencyPercentile(0.5)).isEqualTo(6.37);
  }

  @Test
  void managedPoolMustRecordObjectLifetimeOnDeallocateInConfiguredMetricsRecorder()
      throws InterruptedException {
    CountDownLatch deallocLatch = new CountDownLatch(1);
    builder.setMetricsRecorder(new LastSampleMetricsRecorder());
    builder.setSize(2);
    builder.setAllocator(reallocator(dealloc($countDown(deallocLatch, $null))));
    reducedBackgroundExpirationCheckDelay();
    ManagedPool managedPool = createPool().getManagedPool();
    GenericPoolable a = pool.claim(longTimeout);
    GenericPoolable b = pool.claim(longTimeout);
    spinwait(5);
    a.release();
    b.release();
    pool.setTargetSize(1);
    deallocLatch.await();
    assertThat(managedPool.getObjectLifetimePercentile(0.0))
        .isGreaterThanOrEqualTo(5.0).isNotNaN().isLessThan(50000.0);
  }

  @Test
  void managedPoolMustNotRecordObjectLifetimeLatencyBeforeFirstDeallocation()
      throws InterruptedException {
    builder.setMetricsRecorder(new LastSampleMetricsRecorder());
    ManagedPool managedPool = createPool().getManagedPool();
    GenericPoolable obj = pool.claim(longTimeout);
    try {
      assertThat(managedPool.getObjectLifetimePercentile(0.0)).isNaN();
    } finally {
      obj.release();
    }
  }

  @Test
  void managedPoolMustRecordExplicitlyExpiredObjectLifetimeOnReallocateInMetricsRecorder()
      throws InterruptedException {
    builder.setMetricsRecorder(new LastSampleMetricsRecorder());
    builder.setAllocator(reallocator());
    builder.setExpiration(Expiration.never());
    ManagedPool managedPool = createPool().getManagedPool();
    GenericPoolable obj = pool.claim(longTimeout);
    spinwait(5);
    obj.expire();
    obj.release();
    obj = pool.claim(longTimeout); // wait for reallocation
    try {
      assertThat(managedPool.getObjectLifetimePercentile(0.0))
          .isGreaterThanOrEqualTo(5.0).isNotNaN().isLessThan(50000.0);
    } finally {
      obj.release();
    }
  }

  @Test
  void managedPoolLeakedObjectCountMustStartAtZero() {
    builder.setPreciseLeakDetectionEnabled(true);
    ManagedPool managedPool = createPool().getManagedPool();
    // Pools that don't support this feature return -1L.
    assertThat(managedPool.getLeakedObjectsCount()).isZero();
  }

  @Test
  void managedPoolMustCountLeakedObjects() throws Exception {
    builder.setPreciseLeakDetectionEnabled(true).setSize(2);
    ManagedPool managedPool = createPool().getManagedPool();
    pool.claim(longTimeout); // leak!
    // Clear any thread-local reference to the leaked object:
    pool.claim(longTimeout).release();
    // Clear any references held by our particular allocator:
    allocator.clearLists();

    // Wait for phantom refs to be processed:
    try (AutoCloseable ignore = GarbageCreator.forkCreateGarbage()) {
      GarbageCreator.awaitReferenceProcessing();
    }

    pool = null; // null out the pool because we can no longer shut it down.
    try (var ignore = GarbageCreator.forkCreateGarbage()) {
      for (int i = 0; i < 100; i++) {
        if (managedPool.getLeakedObjectsCount() >= 1) {
          break;
        }
        Thread.sleep(10);
      }
    }
    assertThat(managedPool.getLeakedObjectsCount()).isOne();
  }

  @SuppressWarnings("UnusedAssignment")
  @Test
  void mustNotHoldOnToDeallocatedObjectsWhenLeakDetectionIsEnabled()
      throws Exception {
    // It's enabled by default
    AtomicBoolean hasExpired = new AtomicBoolean();
    builder.setExpiration(expire($expiredIf(hasExpired)));
    createPool();

    // Allocate an object
    GenericPoolable obj = pool.claim(longTimeout);
    WeakReference<GenericPoolable> weakReference = new WeakReference<>(obj);
    obj.release();
    obj = null;

    // Send it back for reallocation
    hasExpired.set(true);
    obj = pool.claim(zeroTimeout);
    if (obj != null) {
      obj.release();
    }
    // Wait for the reallocation to complete
    hasExpired.set(false);
    pool.claim(longTimeout).release();

    // Clear the allocator lists to remove the last references
    allocator.clearLists();

    int iterationCount = 0;
    do {
      // GC to force the object through finalization life cycle
      System.gc();
      assertThat(iterationCount++).isLessThan(1000);

      // Now our weakReference must eventually have been cleared
    } while (weakReference.get() != null);
  }

  @Test
  void mustNotHoldOnToDeallocatedObjectsWhenLeakDetectionIsDisabled()
      throws Exception {
    // It's enabled by default, so just change that setting and run the same
    // test as above
    builder.setPreciseLeakDetectionEnabled(false);
    mustNotHoldOnToDeallocatedObjectsWhenLeakDetectionIsEnabled();
  }

  @Test
  void managedPoolMustNotCountShutDownAsLeak() throws Exception {
    builder.setPreciseLeakDetectionEnabled(true).setSize(2);
    ManagedPool managedPool = createPool().getManagedPool();
    claimRelease(2, pool, longTimeout);
    pool.shutdown().await(longTimeout);
    allocator.clearLists();
    System.gc();
    System.gc();
    System.gc();
    assertThat(managedPool.getLeakedObjectsCount()).isZero();
  }

  @Test
  void managedPoolMustNotCountResizeAsLeak() throws Exception {
    builder.setPreciseLeakDetectionEnabled(true).setSize(2);
    reducedBackgroundExpirationCheckDelay();
    ManagedPool managedPool = createPool().getManagedPool();
    claimRelease(2, pool, longTimeout);
    managedPool.setTargetSize(4);
    claimRelease(4, pool, longTimeout);
    managedPool.setTargetSize(1);
    while (allocator.countDeallocations() < 3) {
      spinwait(1);
    }
    allocator.clearLists();
    System.gc();
    System.gc();
    System.gc();
    assertThat(managedPool.getLeakedObjectsCount()).isZero();
  }

  @Test
  void managedPoolMustReturnMinusOneForLeakedObjectCountWhenDetectionIsDisabled() {
    builder.setPreciseLeakDetectionEnabled(false);
    ManagedPool managedPool = createPool().getManagedPool();
    assertThat(managedPool.getLeakedObjectsCount()).isEqualTo(-1L);
  }

  @Test
  void disabledLeakDetectionMustNotBreakResize() throws Exception {
    builder.setPreciseLeakDetectionEnabled(false);
    builder.setSize(2);
    reducedBackgroundExpirationCheckDelay();
    ManagedPool managedPool = createPool().getManagedPool();
    claimRelease(2, pool, longTimeout);
    pool.setTargetSize(6);
    claimRelease(6, pool, longTimeout);
    pool.setTargetSize(2);
    while (allocator.countDeallocations() < 4) {
      spinwait(1);
    }
    assertThat(managedPool.getLeakedObjectsCount()).isEqualTo(-1L);
  }

  @ParameterizedTest
  @EnumSource(Taps.class)
  void mustDeallocateExplicitlyExpiredObjects(Taps taps) throws Exception {
    int poolSize = 2;
    builder.setSize(poolSize);
    createPool();
    PoolTap<GenericPoolable> tap = taps.get(this);

    // Explicitly expire a pool size worth of objects
    for (int i = 0; i < poolSize; i++) {
      GenericPoolable obj = tap.claim(longTimeout);
      obj.expire();
      obj.release();
    }

    // Grab and release a pool size worth objects as a barrier for reallocating
    // the expired objects
    List<GenericPoolable> objs = new ArrayList<>();
    for (int i = 0; i < poolSize; i++) {
      objs.add(tap.claim(longTimeout));
    }
    for (GenericPoolable obj : objs) {
      obj.release();
    }

    // Now we should see a pool size worth of reallocations
    assertThat(allocator.countAllocations()).as("allocations").isEqualTo(2 * poolSize);
    assertThat(allocator.countDeallocations()).as("deallocations").isEqualTo(poolSize);
  }

  @ParameterizedTest
  @EnumSource(Taps.class)
  void mustReplaceExplicitlyExpiredObjectsEvenIfDeallocationFails(Taps taps)
      throws Exception {
    allocator = allocator(dealloc($throw(new Exception("Boom!"))));
    builder.setAllocator(allocator).setSize(1);
    createPool();
    PoolTap<GenericPoolable> tap = taps.get(this);

    GenericPoolable a = tap.claim(longTimeout);
    a.expire();
    a.release();

    GenericPoolable b = tap.claim(longTimeout);
    b.release();

    assertThat(a).isNotSameAs(b);
  }

  @ParameterizedTest
  @EnumSource(Taps.class)
  void explicitExpiryFromExpirationMustAllowOneClaimPerObject(Taps taps) throws Exception {
    builder.setExpiration(expire($explicitExpire));
    createPool();
    PoolTap<GenericPoolable> tap = taps.get(this);

    GenericPoolable a = tap.claim(longTimeout);
    a.release();

    GenericPoolable b = tap.claim(longTimeout);
    b.release();

    assertThat(a).isNotSameAs(b);
  }

  @ParameterizedTest
  @EnumSource(Taps.class)
  void explicitlyExpiryMustBeIdempotent(Taps taps) throws Exception {
    createPool();
    PoolTap<GenericPoolable> tap = taps.get(this);

    GenericPoolable a = tap.claim(longTimeout);
    a.expire();
    a.expire();
    a.expire();
    a.release();

    GenericPoolable b = tap.claim(longTimeout);
    b.release();

    assertThat(a).isNotSameAs(b);
    assertThat(allocator.countAllocations()).isEqualTo(2);
    assertThat(allocator.countDeallocations()).isOne();
  }

  @ParameterizedTest
  @EnumSource(Taps.class)
  void newlyAllocatedObjectsMustBeClaimedAheadOfExistingLiveObjects(Taps taps) throws Exception {
    noBackgroundExpirationChecking();
    createPoolOfSize(3);
    PoolTap<GenericPoolable> tap = taps.get(this);

    while (pool.getManagedPool().getAllocationCount() < 3) {
      Thread.yield();
    }

    // Last object to be allocated is the first to be claimed.
    GenericPoolable a = tap.claim(longTimeout);
    try {
      List<GenericPoolable> allocations = allocator.getAllocations();
      assertThat(a).isSameAs(allocations.getLast());
    } finally {
      a.release();
    }
  }

  @ParameterizedTest
  @EnumSource(Taps.class)
  void claimMustUnblockByConcurrentAllocation(Taps taps) throws Exception {
    createOneObjectPool();
    PoolTap<GenericPoolable> tap = taps.get(this);
    GenericPoolable obj = pool.claim(longTimeout);
    Thread thread = fork(() -> {
      tap.claim(longTimeout).release();
      return null;
    });
    waitForThreadState(thread, Thread.State.TIMED_WAITING);
    obj.expire();
    obj.release();
    join(thread);
  }

  @ParameterizedTest
  @EnumSource(Taps.class)
  void applyMustUnblockByConcurrentAllocation(Taps taps) throws Exception {
    createOneObjectPool();
    PoolTap<GenericPoolable> tap = taps.get(this);
    GenericPoolable obj = pool.claim(longTimeout);
    Thread thread = fork(() -> tap.apply(longTimeout, identity()));
    waitForThreadState(thread, Thread.State.TIMED_WAITING);
    obj.expire();
    obj.release();
    join(thread);
  }

  @ParameterizedTest
  @EnumSource(Taps.class)
  void supplyMustUnblockByConcurrentAllocation(Taps taps) throws Exception {
    createOneObjectPool();
    PoolTap<GenericPoolable> tap = taps.get(this);
    GenericPoolable obj = pool.claim(longTimeout);
    Thread thread = fork(() -> tap.supply(longTimeout, nullConsumer));
    waitForThreadState(thread, Thread.State.TIMED_WAITING);
    obj.expire();
    obj.release();
    join(thread);
  }

  @ParameterizedTest
  @EnumSource(Taps.class)
  void claimMustUnblockByConcurrentReAllocation(Taps taps) throws Exception {
    builder.setAllocator(reallocator());
    createOneObjectPool();
    PoolTap<GenericPoolable> tap = taps.get(this);
    GenericPoolable obj = pool.claim(longTimeout);
    Thread thread = fork(() -> {
      tap.claim(longTimeout).release();
      return null;
    });
    waitForThreadState(thread, Thread.State.TIMED_WAITING);
    obj.expire();
    obj.release();
    join(thread);
  }

  @ParameterizedTest
  @EnumSource(Taps.class)
  void applyMustUnblockByConcurrentReAllocation(Taps taps) throws Exception {
    builder.setAllocator(reallocator());
    createOneObjectPool();
    PoolTap<GenericPoolable> tap = taps.get(this);
    GenericPoolable obj = pool.claim(longTimeout);
    Thread thread = fork(() -> tap.apply(longTimeout, identity()));
    waitForThreadState(thread, Thread.State.TIMED_WAITING);
    obj.expire();
    obj.release();
    join(thread);
  }

  @ParameterizedTest
  @EnumSource(Taps.class)
  void supplyMustUnblockByConcurrentReAllocation(Taps taps) throws Exception {
    builder.setAllocator(reallocator());
    createOneObjectPool();
    PoolTap<GenericPoolable> tap = taps.get(this);
    GenericPoolable obj = pool.claim(longTimeout);
    Thread thread = fork(() -> tap.supply(longTimeout, nullConsumer));
    waitForThreadState(thread, Thread.State.TIMED_WAITING);
    obj.expire();
    obj.release();
    join(thread);
  }

  @ParameterizedTest
  @EnumSource(Taps.class)
  void explicitlyExpiredObjectsMustBeDeallocated(Taps taps) throws Exception {
    createOneObjectPool();
    PoolTap<GenericPoolable> tap = taps.get(this);
    GenericPoolable a = tap.claim(longTimeout);
    a.expire();
    a.release();
    tap.claim(longTimeout).release();
    List<GenericPoolable> deallocations = allocator.getDeallocations();
    synchronized (deallocations) {
      assertThat(deallocations).contains(a);
    }
  }


  @ParameterizedTest
  @EnumSource(Taps.class)
  void shutDownMustDeallocateExplicitlyExpiredObjects(Taps taps) throws Exception {
    createOneObjectPool();
    PoolTap<GenericPoolable> tap = taps.get(this);
    GenericPoolable a = tap.claim(longTimeout);
    a.expire();
    Completion shutdown = pool.shutdown();
    a.release();
    shutdown.await(longTimeout);
    assertEquals(allocator.countAllocations(), 1);
    assertEquals(allocator.countDeallocations(), 1);
    List<GenericPoolable> deallocations = allocator.getDeallocations();
    synchronized (deallocations) {
      assertThat(deallocations).contains(a);
    }
  }

  @Test
  void virtualThreadSafeTapEnsureExclusiveAccessToClaimObjects() throws Exception {
    class IntCounter extends BasePoolable {
      int counter;

      public IntCounter(Slot slot) {
        super(slot);
      }
    }
    AtomicInteger sum = new AtomicInteger();
    PoolBuilder<IntCounter> builder = createInitialPoolBuilder(new Allocator<>() {
      @Override
      public IntCounter allocate(Slot slot) {
        return new IntCounter(slot);
      }

      @Override
      public void deallocate(IntCounter poolable) {
        sum.addAndGet(poolable.counter);
      }
    });
    builder.setSize(60).setExpiration(Expiration.never());
    if (builder.isBackgroundExpirationEnabled()) {
      builder.setBackgroundExpirationEnabled(false);
    }
    Pool<IntCounter> pool = builder.build();
    AtomicReference<Exception> exception = new AtomicReference<>();
    PoolTap<IntCounter> tap = pool.getVirtualThreadSafeTap();
    Runnable task = () -> {
      for (int i = 0; i < 1000; i++) {
        try {
          IntCounter claim = tap.claim(longTimeout);
          if (claim == null) {
            i--;
            continue;
          }
          int counter = claim.counter;
          Thread.yield();
          claim.counter = counter + 1;
          claim.release();
        } catch (InterruptedException e) {
          exception.set(e);
        }
      }
    };
    List<Thread> threads = new ArrayList<>();
    for (int i = 0; i < 100; i++) {
      threads.add(Thread.ofVirtual().start(task));
    }
    for (Thread thread : threads) {
      thread.join();
    }
    assertTrue(pool.shutdown().await(longTimeout));
    assertThat(sum).hasValue(100_000);
    Exception e = exception.get();
    if (e != null) {
      fail("A virtual thread encountered an exception", e);
    }
  }

  // NOTE: When adding, removing or modifying tests, also remember to update
  //       the javadocs and docs pages.
}
