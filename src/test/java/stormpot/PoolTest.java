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

import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.experimental.theories.DataPoint;
import org.junit.experimental.theories.Theories;
import org.junit.experimental.theories.Theory;
import org.junit.rules.TestRule;
import org.junit.runner.RunWith;
import stormpot.bpool.BlazePoolFixture;
import stormpot.qpool.QueuePoolFixture;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.locks.Lock;
import java.util.concurrent.locks.LockSupport;
import java.util.concurrent.locks.ReentrantLock;

import static org.hamcrest.Matchers.*;
import static org.junit.Assert.*;
import static org.junit.Assume.assumeThat;
import static stormpot.UnitKit.*;

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
 * used to construct and initialise the pool, based on a {@link Config}.
 * <p>
 * The only assumptions used in this test, is whether the Pool is a
 * LifecycledPool or not. And most interesting pools are life-cycled.
 * LifecycledPools can be shut down. This is a required ability, in order to
 * test a number of behaviours, but also brings about its own set of new
 * behaviours and flows that needs to be tested for. Those tests are also
 * included in this test case.
 * <p>
 * <strong>Note:</strong> when adding, removing or modifying tests, also
 * remember to update the {@link Pool} javadoc - especially the part about
 * what promises are provided by the Pool interface and its implementations.
 * 
 * @author Chris Vest &lt;mr.chrisvest@gmail.com&gt;
 * @see Pool
 */
@RunWith(Theories.class)
public class PoolTest {
  @Rule public final TestRule failurePrinter = new FailurePrinterTestRule();
  
  private static final Expiration<Poolable> oneMsTTL =
      new TimeExpiration(1, TimeUnit.MILLISECONDS);
  private static final Expiration<Poolable> fiveMsTTL =
      new TimeExpiration(5, TimeUnit.MILLISECONDS);
  private static final Timeout longTimeout = new Timeout(1, TimeUnit.SECONDS);
  private static final Timeout mediumTimeout = new Timeout(10, TimeUnit.MILLISECONDS);
  private static final Timeout shortTimeout = new Timeout(1, TimeUnit.MILLISECONDS);
  private static final Timeout zeroTimeout = new Timeout(0, TimeUnit.MILLISECONDS);
  
  private CountingAllocator allocator;
  private Config<GenericPoolable> config;

  @DataPoint public static PoolFixture queuePool = new QueuePoolFixture();
  @DataPoint public static PoolFixture blazePool = new BlazePoolFixture();
  
  @Before public void
  setUp() {
    allocator = new CountingAllocator();
    config = new Config<GenericPoolable>().setSize(1).setAllocator(allocator);
  }

  private LifecycledPool<GenericPoolable> lifecycled(PoolFixture fixture) {
    Pool<GenericPoolable> pool = fixture.initPool(config);
    assumeThat(pool, instanceOf(LifecycledPool.class));
    return (LifecycledPool<GenericPoolable>) pool;
  }

  private ResizablePool<GenericPoolable> resizable(PoolFixture fixture) {
    Pool<GenericPoolable> pool = fixture.initPool(config);
    assumeThat(pool, instanceOf(ResizablePool.class));
    return (ResizablePool<GenericPoolable>) pool;
  }

  private LifecycledResizablePool<GenericPoolable> lifecycledResizable(PoolFixture fixture) {
    Pool<GenericPoolable> pool = fixture.initPool(config);
    assumeThat(pool, instanceOf(LifecycledResizablePool.class));
    return (LifecycledResizablePool<GenericPoolable>) pool;
  }
  
  @Test(expected = IllegalArgumentException.class)
  @Theory public void
  timeoutCannotBeNull(PoolFixture fixture) throws Exception {
    fixture.initPool(config).claim(null);
  }
  
  /**
   * A call to claim must return before the timeout elapses if it
   * can claim an object from the pool, and it must return that object.
   * The timeout for the claim is longer than the timeout for the test, so we
   * know that we won't get a null back here because the timeout wasn't long
   * enough. If we do, then the pool does not correctly implement the timeout
   * behaviour.
   */
  @Test(timeout = 601)
  @Theory public void
  claimMustReturnIfWithinTimeout(PoolFixture fixture)
  throws Exception {
    Pool<GenericPoolable> pool = fixture.initPool(config);
    Poolable obj = pool.claim(longTimeout);
    assertThat(obj, not(nullValue()));
  }
  
  /**
   * A call to claim that fails to get an object before the
   * timeout elapses, must return null.
   * We test this by depleting a pool, and then make a call to claim with
   * a shot timeout. If that call returns <code>null</code>, then we're good.
   */
  @Test(timeout = 601)
  @Theory public void
  claimMustReturnNullIfTimeoutElapses(PoolFixture fixture)
  throws Exception {
    Pool<GenericPoolable> pool = fixture.initPool(config);
    pool.claim(longTimeout); // pool is now depleted
    Poolable obj = pool.claim(shortTimeout);
    assertThat(obj, is(nullValue()));
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
  @Test(timeout = 601)
  @Theory public void
  mustGetPooledObjectsFromAllocator(PoolFixture fixture) throws Exception {
    Pool<GenericPoolable> pool = fixture.initPool(config);
    pool.claim(longTimeout);
    assertThat(allocator.allocations(), is(greaterThan(0)));
  }
  
  /**
   * If the pool has been depleted for objects, then a call to claim with
   * timeout will wait until either an object becomes available, or the timeout
   * elapses. Whichever comes first.
   * We test for this by observing that a thread that makes a claim-with-timeout
   * call to a depleted pool, will enter the TIMED_WAITING state.
   */
  @Test(timeout = 601)
  @Theory public void
  blockingClaimWithTimeoutMustWaitIfPoolIsEmpty(PoolFixture fixture)
  throws Exception {
    Pool<GenericPoolable> pool = fixture.initPool(config);
    assertNotNull("Did not deplete pool in time", pool.claim(longTimeout));
    Thread thread = fork($claim(pool, longTimeout));
    waitForThreadState(thread, Thread.State.TIMED_WAITING);
  }

  /**
   * A thread that is waiting in claim-with-timeout on a depleted pool must
   * wake up if another thread releases an object back into the pool.
   * So if we deplete a pool, make a thread wait in claim-with-timeout and
   * then release an object back into the pool, then we must be able to join
   * to that thread.
   */
  @Test(timeout = 601)
  @Theory public void
  blockingOnClaimWithTimeoutMustResumeWhenPoolablesAreReleased(
      PoolFixture fixture) throws Exception {
    Pool<GenericPoolable> pool = fixture.initPool(config);
    Poolable obj = pool.claim(longTimeout);
    assertNotNull("Did not deplete pool in time", obj);
    Thread thread = fork($claim(pool, longTimeout));
    waitForThreadState(thread, Thread.State.TIMED_WAITING);
    obj.release();
    join(thread);
  }
  
  /**
   * One uses a pool because a certain type of objects are expensive to
   * create and we would like to recycle them. So when we claim and object,
   * then release it back into the pool, and then claim and release it again,
   * then we must observe that only a single object allocation has taken
   * place.
   * The pool has a size of 1, so we can safely base this test on the
   * allocation count - even for pools that like to eagerly saturate the
   * pool with objects.
   */
  @Test(timeout = 601)
  @Theory public void
  mustReuseAllocatedObjects(PoolFixture fixture)
  throws Exception {
    Pool<GenericPoolable> pool = fixture.initPool(config);
    pool.claim(longTimeout).release();
    pool.claim(longTimeout).release();
    assertThat(allocator.allocations(), is(1));
  }
  
  /**
   * Be careful and prevent the creation of pools with a size less than one.
   * The contract of claim is to block indefinitely if one such pool were
   * to be created.
   * @see Config#setSize(int)
   */
  @Test(timeout = 601, expected = IllegalArgumentException.class)
  @Theory public void
  constructorMustThrowOnPoolSizeLessThanOne(PoolFixture fixture) {
    fixture.initPool(config.setSize(0));
  }

  /**
   * Prevent the creation of pools with a null Expiration.
   * @see Config#setExpiration(Expiration)
   */
  @Test(timeout = 601, expected = IllegalArgumentException.class)
  @Theory public void
  constructorMustThrowOnNullExpiration(PoolFixture fixture) {
    fixture.initPool(config.setExpiration(null));
  }
  
  /**
   * Prevent the creation of pools with a null Allocator.
   * @see Config#setAllocator(Allocator)
   */
  @Test(timeout = 601, expected = IllegalArgumentException.class)
  @Theory public void
  constructorMustThrowOnNullAllocator(PoolFixture fixture) {
    fixture.initPool(config.setAllocator(null));
  }
  
  /**
   * Pools must use the provided expiration to determine whether slots
   * are invalid or not, instead of using their own ad-hoc mechanisms.
   * We test this by using an expiration that counts the number of times
   * it is invoked. Then we claim an object and assert that the expiration
   * was invoked at least once, presumably for that object.
   */
  @Test(timeout = 601)
  @Theory public void
  mustUseProvidedExpiration(PoolFixture fixture) throws Exception {
    CountingExpiration expiration = new CountingExpiration(false);
    config.setExpiration(expiration);
    Pool<GenericPoolable> pool = fixture.initPool(config);
    pool.claim(longTimeout).release();
    assertThat(expiration.getCount(), greaterThanOrEqualTo(1));
  }
  
  /**
   * In the hopefully unlikely event that an Expiration throws an
   * exception, that exception should bubble out of the pool unspoiled.
   * 
   * We test for this by configuring an Expiration that always throws.
   * No guarantees are being made about when, exactly, it is that the pool will
   * invoke the Expiration. Therefore we claim and release an object a
   * couple of times. That ought to do it.
   */
  @Test(timeout = 601, expected = SomeRandomException.class)
  @Theory public void
  exceptionsFromExpirationMustBubbleOut(PoolFixture fixture)
      throws Throwable {
    config.setExpiration(new ThrowyExpiration());
    Pool<GenericPoolable> pool = fixture.initPool(config);
    
    try {
      // make a couple of calls because pools might optimise for freshly
      // created objects
      pool.claim(longTimeout).release();
      pool.claim(longTimeout).release();
    } catch (PoolException e) {
      throw e.getCause();
    }
  }
  
  /**
   * If the Expiration throws an exception when evaluating a slot, then that
   * slot should be considered invalid.
   * 
   * We test for this by configuring an expiration that always throws,
   * and then we make a claim and a release to make sure that it got invoked.
   * Then, since the pool size is one, we make another claim to make sure that
   * the invalid slot got reallocated. We don't care if that second claim
   * throws or not. All we're interested in, is whether the deallocation took
   * place.
   */
  @Test(timeout = 601)
  @Theory public void
  slotsThatMakeTheExpirationThrowAreInvalid(PoolFixture fixture)
      throws Exception {
    config.setExpiration(new ThrowyExpiration());
    Pool<GenericPoolable> pool = fixture.initPool(config);
    try {
      pool.claim(longTimeout);
      fail("should throw");
    } catch (PoolException e) {
      assertThat(e.getCause(), instanceOf(SomeRandomException.class));
    }
    // second call to claim to ensure that the deallocation has taken place
    try {
      pool.claim(longTimeout);
    } catch (PoolException e) {
      assertThat(e.getCause(), instanceOf(SomeRandomException.class));
    }
    // must have deallocated that object
    assertThat(allocator.deallocations(), greaterThanOrEqualTo(1));
  }
  
  /**
   * SlotInfo objects offer a count of how many times the Poolable it
   * represents, has been claimed. Naturally, this count must increase every
   * time that object is claimed.
   * We test for this by creating an Expiration that writes the count to
   * an atomic every time it is called. Then we make a couple of claims and
   * releases, and assert that the recorded count has gone up.
   */
  @Test(timeout = 601)
  @Theory public void
  slotInfoClaimCountMustIncreaseWithClaims(PoolFixture fixture) throws Exception {
    final AtomicLong lastClaimCount = new AtomicLong();
    Expiration<Poolable> expiration = new Expiration<Poolable>() {
      public boolean hasExpired(SlotInfo<? extends Poolable> info) {
        lastClaimCount.set(info.getClaimCount());
        return false;
      }
    };
    config.setExpiration(expiration);
    Pool<GenericPoolable> pool = fixture.initPool(config);
    pool.claim(longTimeout).release();
    pool.claim(longTimeout).release();
    pool.claim(longTimeout).release();
    // we have made claims, and the expiration ought to have noted this
    assertThat(lastClaimCount.get(), greaterThan(1L));
  }
  
  /**
   * Expirations might require access to the actual object in question
   * being pooled, in order to implement advanced and/or domain specific
   * logic.
   * As with the claim count test above, we configure an expiration
   * that puts the value into an atomic. Then we assert that the value of the
   * atomic is one of the claimed objects.
   */
  @SuppressWarnings("unchecked")
  @Test(timeout = 601)
  @Theory public void
  slotInfoMustHaveReferenceToItsPoolable(PoolFixture fixture) throws Exception {
    final AtomicReference<? super Poolable> lastPoolable =
        new AtomicReference<Poolable>();
    Expiration<Poolable> expiration = new Expiration<Poolable>() {
      public boolean hasExpired(SlotInfo<? extends Poolable> info) {
        lastPoolable.set(info.getPoolable());
        return false;
      }
    };
    config.setExpiration(expiration);
    Pool<GenericPoolable> pool = fixture.initPool(config);
    GenericPoolable a = pool.claim(longTimeout);
    a.release();
    GenericPoolable b = pool.claim(longTimeout);
    b.release();
    GenericPoolable poolable = (GenericPoolable) lastPoolable.get();
    assertThat(poolable, anyOf(is(a), is(b)));
  }
  
  /**
   * SlotInfo instances must have a means of acting as a non-contended source
   * of random numbers. We test this by getting a hold of a SlotInfo instance,
   * and then pulling a large quantity of random numbers from it. If the
   * numbers are random, then they will have a roughly even split between ones
   * and zero bits.
   */
  @Test(timeout = 601)
  @Theory public void
  slotInfoMustBeAbleToProduceRandomNumbers(PoolFixture fixture) throws Exception {
    final AtomicReference<SlotInfo<? extends Poolable>> slotInfoRef =
        new AtomicReference<SlotInfo<? extends Poolable>>();
    Expiration<Poolable> expiration = new Expiration<Poolable>() {
      public boolean hasExpired(SlotInfo<? extends Poolable> info)
          throws Exception {
        slotInfoRef.set(info);
        return false;
      }
    };
    config.setExpiration(expiration);
    Pool<GenericPoolable> pool = fixture.initPool(config);
    pool.claim(longTimeout).release(); // Now we have a SlotInfo reference.
    SlotInfo<? extends Poolable> slotInfo = slotInfoRef.get();
    
    // A full suite for testing the quality of the PRNG would be excessive here.
    // We just want a back-of-the-envelope estimate that it's random.
    int nums = 1000000;
    int bits = 32 * nums;
    int ones = 0;
    for (int i = 0; i < nums; i++) {
      ones += Integer.bitCount(slotInfo.randomInt());
    }
    // In the random data that we collect, we should see a roughly even split
    // in the bits between ones and zeros.
    // So, if we count all the one bits and double that number, we should get
    // a number that is very close to the total number of random bits generated.
    double diff = Math.abs(bits - ones * 2);
    assertThat(diff, lessThan(bits * 0.005));
  }
  
  /**
   * Pool implementations might reuse their SlotInfo instances. We need to
   * make sure that if an object is reallocated, then the claim count for that
   * slot is reset to zero.
   * We test for this by configuring an expiration that invalidates
   * objects that have been claimed more than once, and records the maximum
   * claim count it observes in an atomic. Then we make more claims than this
   * limit, and observe that precisely one more than the max have been observed.
   */
  @Test(timeout = 601)
  @Theory public void
  slotInfoClaimCountMustResetIfSlotsAreReused(PoolFixture fixture) throws Exception {
    final AtomicLong maxClaimCount = new AtomicLong();
    Expiration<Poolable> expiration = new Expiration<Poolable>() {
      public boolean hasExpired(SlotInfo<? extends Poolable> info) {
        maxClaimCount.set(Math.max(maxClaimCount.get(), info.getClaimCount()));
        return info.getClaimCount() > 1;
      }
    };
    config.setExpiration(expiration);
    Pool<GenericPoolable> pool = fixture.initPool(config);
    pool.claim(longTimeout).release();
    pool.claim(longTimeout).release();
    pool.claim(longTimeout).release();
    // we've made 3 claims, while all objects w/ claimCount > 1 are invalid
    assertThat(maxClaimCount.get(), is(2L));
  }
  
  /**
   * Verify that we can read back a stamp value from the SlotInfo that we have
   * set, when the Slot has not been re-allocated.
   * We test this with an Expiration that set the stamp value if it is zero,
   * or set a flag to signify that it has been remembered, if it is the value
   * we set it to. Then we claim and release a couple of times. If it works,
   * the second claim+release pair would have raised the flag.
   */
  @Test(timeout = 601)
  @Theory public void
  slotInfoMustRememberStamp(PoolFixture fixture) throws Exception {
    final AtomicBoolean rememberedStamp = new AtomicBoolean();
    Expiration<Poolable> expiration = new Expiration<Poolable>() {
      public boolean hasExpired(SlotInfo<? extends Poolable> info) throws Exception {
        long stamp = info.getStamp();
        if (stamp == 0) {
          info.setStamp(13);
        } else if (stamp == 13) {
          rememberedStamp.set(true);
        }
        return false;
      }
    };
    config.setExpiration(expiration);
    Pool<GenericPoolable> pool = fixture.initPool(config);
    pool.claim(longTimeout).release(); // First set it...
    pool.claim(longTimeout).release(); // ... then get it.
    assertTrue(rememberedStamp.get());
  }
  
  /**
   * The SlotInfo stamp is zero by default. This must also be true of Slots
   * that has had their object reallocated. So if we set the stamp, then
   * reallocate the Poolable, then we should observe that the stamp is now back
   * to zero again.
   */
  @Test(timeout = 601)
  @Theory public void
  slotInfoStampMustResetIfSlotsAreReused(PoolFixture fixture) throws Exception {
    final AtomicLong zeroStampsCounted = new AtomicLong(0);
    Expiration<Poolable> expiration = new Expiration<Poolable>() {
      public boolean hasExpired(SlotInfo<? extends Poolable> info)
          throws Exception {
        long stamp = info.getStamp();
        info.setStamp(15);
        if (stamp == 0) {
          zeroStampsCounted.incrementAndGet();
          return false;
        }
        return true;
      }
    };
    config.setExpiration(expiration);
    Pool<GenericPoolable> pool = fixture.initPool(config);
    
    pool.claim(longTimeout).release();
    pool.claim(longTimeout).release();
    pool.claim(longTimeout).release();
    
    assertThat(zeroStampsCounted.get(), is(3L));
  }
  
  /**
   * It is not possible to claim from a pool that has been shut down. Doing
   * so will cause an IllegalStateException to be thrown. This must take
   * effect as soon as shutdown has been called. So the fact that claim()
   * becomes unusable happens-before the pool shutdown process completes.
   * The memory effects of this are not tested for, but I don't think it is
   * possible to implement in a thread-safe manner and not provide the
   * memory effects that we want.
   */
  @Test(timeout = 601, expected = IllegalStateException.class)
  @Theory public void
  preventClaimFromPoolThatIsShutDown(PoolFixture fixture)
  throws Exception {
    LifecycledPool<GenericPoolable> pool = lifecycled(fixture);
    pool.claim(longTimeout).release();
    pool.shutdown();
    pool.claim(longTimeout);
  }

  /**
   * Objects in the pool only live for a certain amount of time, and then
   * they must be replaced/renewed. Pools should generally try to renew
   * before the timeout elapses for the given object, but we don't test for
   * that here.
   * We set the TTL to be 1 milliseconds, because that is short enough that
   * we can wait for it in a spin-loop. This way, the objects will always
   * appear to have expired when checked. This means that every claim will
   * always allocate a new object, and so our two claims will translate to
   * at least two allocations, which is what we check for. Note that the TTL
   * is so short that newly allocated objects might actually expire before a
   * claim call can complete, and thus more than the expected two allocations
   * are possible. This is why we check for *at least* two allocations.
   */
  @Test(timeout = 601)
  @Theory public void
  mustReplaceExpiredPoolables(PoolFixture fixture) throws Exception {
    Pool<GenericPoolable> pool = fixture.initPool(
        config.setExpiration(oneMsTTL));
    pool.claim(longTimeout).release();
    spinwait(2);
    pool.claim(longTimeout).release();
    assertThat(allocator.allocations(), greaterThanOrEqualTo(2));
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
   * @see Config#setSize(int)
   */
  @Test(timeout = 601)
  @Theory public void
  mustDeallocateExpiredPoolablesAndStayWithinSizeLimit(PoolFixture fixture)
  throws Exception {
    Pool<GenericPoolable> pool = fixture.initPool(
        config.setExpiration(oneMsTTL));
    pool.claim(longTimeout).release();
    spinwait(2);
    pool.claim(longTimeout).release();
    assertThat(allocator.deallocations(), greaterThanOrEqualTo(1));
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
  @Test(timeout = 601)
  @Theory public void
  mustDeallocateAllPoolablesBeforeShutdownTaskReturns(PoolFixture fixture)
  throws Exception {
    config.setSize(2);
    LifecycledPool<GenericPoolable> pool = lifecycled(fixture);
    Poolable p1 = pool.claim(longTimeout);
    Poolable p2 = pool.claim(longTimeout);
    p1.release();
    p2.release();
    pool.shutdown().await(longTimeout);
    assertThat(allocator.deallocations(), is(2));
  }
  
  /**
   * So awaiting the shut down completion cannot return before all
   * claimed objects are both released and deallocated. Likewise, the
   * initiation of the shut down process - the call to shutdown() - must
   * decidedly NOT wait for any claimed objects to be released, before the
   * call returns.
   * We test for this effect by creating a pool and claiming and object
   * without ever releasing it. Then we call shutdown, without ever awaiting
   * its completion. The test passes if this does not dead-lock, hence the
   * test timeout.
   */
  @Test(timeout = 601)
  @Theory public void
  shutdownCallMustReturnFastIfPoolablesAreStillClaimed(PoolFixture fixture)
  throws Exception {
    LifecycledPool<GenericPoolable> pool = lifecycled(fixture);
    assertNotNull("Did not deplete pool in time", pool.claim(longTimeout));
    pool.shutdown();
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
  @Test(timeout = 601)
  @Theory public void
  shutdownMustNotDeallocateClaimedPoolables(PoolFixture fixture)
  throws Exception {
    LifecycledPool<GenericPoolable> pool = lifecycled(fixture);
    assertNotNull("Did not deplete pool in time", pool.claim(longTimeout));
    pool.shutdown().await(mediumTimeout);
    assertThat(allocator.deallocations(), is(0));
  }
  
  /**
   * We know from the previous test, that awaiting the shut down completion
   * will wait for any claimed objects to be released. However, once those
   * objects are released, we must also make sure that the shut down process
   * actually resumes and eventually completes as a result.
   * We test this by claiming and object and starting the shut down process.
   * Then we set another thread to await the completion of the shut down
   * process, and make sure that it actually enters the WAITING state.
   * Then we release the claimed object and try to join the thread. If we
   * manage to join the thread, then the shut down process has completed, and
   * the test pass if this all happens within the test timeout.
   * When a thread is in the WAITING state, it means that it is waiting for
   * another thread to do something that will let it resume. In our case,
   * the thread is waiting for someone to release the claimed object.
   */
  @Test(timeout = 601)
  @Theory public void
  awaitOnShutdownMustReturnWhenClaimedObjectsAreReleased(PoolFixture fixture)
  throws Exception {
    LifecycledPool<GenericPoolable> pool = lifecycled(fixture);
    Poolable obj = pool.claim(longTimeout);
    Completion completion = pool.shutdown();
    Thread thread = fork($await(completion, longTimeout));
    waitForThreadState(thread, Thread.State.TIMED_WAITING);
    obj.release();
    join(thread);
  }
  
  /**
   * The await with timeout on the Completion of the shut down process
   * must return false if the timeout elapses, as is the typical contract
   * of such methods in java.util.concurrent.
   * We are going to assume that the implementation adheres to the requested
   * timeout within reasonable margins, because the implementations are
   * probably going to delegate this call to java.util.concurrent anyway.
   */
  @Test(timeout = 601)
  @Theory public void
  awaitWithTimeoutMustReturnFalseIfTimeoutElapses(PoolFixture fixture)
  throws Exception {
    LifecycledPool<GenericPoolable> pool = lifecycled(fixture);
    Poolable obj = pool.claim(longTimeout);
    assertFalse(pool.shutdown().await(shortTimeout));
    obj.release();
  }
  
  /**
   * We have verified that await with timeout returns false if the timeout
   * elapses. We also have to make sure that the call returns true if the
   * shut down process completes within the timeout.
   * We test for this by claiming an object, start the shut down process,
   * set a thread to await the completion with a timeout, then release the
   * claimed object and join the thread. The result will be put in an
   * AtomicBoolean, which then must contain true after the thread has been
   * joined. And this must all happen before the test itself times out.
   */
  @Test(timeout = 601)
  @Theory public void
  awaitWithTimeoutMustReturnTrueIfCompletesWithinTimeout(PoolFixture fixture)
  throws Exception {
    LifecycledPool<GenericPoolable> pool = lifecycled(fixture);
    Poolable obj = pool.claim(longTimeout);
    AtomicBoolean result = new AtomicBoolean(false);
    Completion completion = pool.shutdown();
    Thread thread =
      fork($await(completion, longTimeout, result));
    waitForThreadState(thread, Thread.State.TIMED_WAITING);
    obj.release();
    join(thread);
    assertTrue(result.get());
  }
  
  /**
   * We have verified that the await method works as intended, if you
   * begin your awaiting while the shut down process is still undergoing.
   * However, we must also make sure that further calls to await after the
   * shut down process has completed, do not block.
   * We do this by shutting a pool down, and then make a number of await calls
   * to the shut down Completion. These calls must all return before the
   * timeout of the test elapses.
   */
  @Test(timeout = 601)
  @Theory public void
  awaitingOnAlreadyCompletedShutDownMustNotBlock(PoolFixture fixture)
  throws Exception {
    LifecycledPool<GenericPoolable> pool = lifecycled(fixture);
    Completion completion = pool.shutdown();
    completion.await(longTimeout);
    completion.await(longTimeout);
  }
  
  /**
   * A call to claim on a pool that has been, or is in the process of being,
   * shut down, will throw an IllegalStateException. So should calls that
   * are blocked on claim when the shut down process is initiated.
   * To test this, we create a pool with one object and claim it. Then we
   * set another thread to also claim an object. This thread will block
   * because the pool has been depleted. To make sure of this, we wait for
   * the thread to enter the WAITING state. Then we start the shut down
   * process of the pool, release the object and join the thread we started.
   * If the call to claim throws an exception in the other thread, then it
   * will be put in an AtomicReference, and we assert that it is indeed an
   * IllegalStateException.
   */
  @Test(timeout = 601)
  @Theory public void
  blockedClaimMustThrowWhenPoolIsShutDown(PoolFixture fixture)
  throws Exception {
    LifecycledPool<GenericPoolable> pool = lifecycled(fixture);
    AtomicReference<Exception> caught = new AtomicReference<Exception>();
    Poolable obj = pool.claim(longTimeout);
    Thread thread = fork($catchFrom($claim(pool, longTimeout), caught));
    waitForThreadState(thread, Thread.State.TIMED_WAITING);
    pool.shutdown();
    obj.release();
    join(thread);
    assertThat(caught.get(), instanceOf(IllegalStateException.class));
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
  @Test(timeout = 601)
  @Theory public void
  mustNotDeallocateTheSameObjectMoreThanOnce(PoolFixture fixture)
  throws Exception {
    Pool<GenericPoolable> pool = fixture.initPool(
        config.setExpiration(oneMsTTL));
    Poolable obj = pool.claim(longTimeout);
    spinwait(2);
    obj.release();
    for (int i = 0; i < 10; i++) {
      try {
        obj.release();
      } catch (Exception _) {
        // we don't really care if the pool is able to detect this or not
        // we are still going to check with the Allocator.
      }
    }
    pool.claim(longTimeout);
    // check if the deallocation list contains duplicates
    List<GenericPoolable> deallocations = allocator.deallocationList();
    for (Poolable elm : deallocations) {
      assertThat("Deallocations of " + elm,
          Collections.frequency(deallocations, elm), is(1));
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
  @Test(timeout = 601)
  @Theory public void
  shutdownMustNotDeallocateEmptySlots(PoolFixture fixture) throws Exception {
    final AtomicBoolean wasNull = new AtomicBoolean();
    Allocator<GenericPoolable> allocator = new CountingAllocator() {
      @Override
      public void deallocate(GenericPoolable poolable) {
        if (poolable == null) {
          wasNull.set(true);
        }
      }
    };
    config.setAllocator(allocator).setExpiration(oneMsTTL);
    LifecycledPool<GenericPoolable> pool = lifecycled(fixture);
    pool.claim(longTimeout).release();
    pool.shutdown().await(longTimeout);
    assertFalse(wasNull.get());
  }
  
  /**
   * Pools must be prepared in the event that an Allocator throws an
   * Exception from allocate. If it is not possible to allocate an object,
   * then the pool must throw a PoolException through claim.
   * @see PoolException
   */
  @Test(timeout = 601)
  @Theory public void
  mustPropagateExceptionsFromAllocateThroughClaim(PoolFixture fixture)
  throws Exception {
    final RuntimeException expectedEception = new RuntimeException("boo");
    Allocator<GenericPoolable> allocator = new CountingAllocator() {
      @Override
      public GenericPoolable allocate(Slot slot) {
        throw expectedEception;
      }
    };
    Pool<GenericPoolable> pool = fixture.initPool(config.setAllocator(allocator));
    try {
      pool.claim(longTimeout);
      fail("expected claim to throw");
    } catch (PoolException poolException) {
      assertThat(poolException.getCause(), is((Throwable) expectedEception));
    }
  }

  /**
   * Pools must be prepared in the event that a Reallocator throws an
   * Exception from reallocate. If it is not possible to reallocate an object,
   * then the pool must throw a PoolException through claim.
   * @see PoolException
   */
  @Test(timeout = 601)
  @Theory public void
  mustPropagateExceptionsFromReallocateThroughClaim(PoolFixture fixture)
      throws Exception {
    final RuntimeException expectedException = new RuntimeException("boo");
    Reallocator<GenericPoolable> allocator = new CountingReallocator() {
      @Override
      public GenericPoolable reallocate(Slot slot, GenericPoolable poolable) throws Exception {
        throw expectedException;
      }
    };
    Expiration<Poolable> expiration = new CountingExpiration(true, false);
    Pool<GenericPoolable> pool = fixture.initPool(
        config.setAllocator(allocator).setExpiration(expiration));
    try {
      pool.claim(longTimeout);
      fail("expected claim to throw");
    } catch (PoolException poolException) {
      assertThat(poolException.getCause(), is((Throwable) expectedException));
    }
  }
  
  /**
   * A pool must not break its internal invariants if an Allocator throws an
   * exception in allocate, and it must still be usable after the exception
   * has bubbled out.
   * We test this by configuring an Allocator that throws an exception if a
   * boolean variable is true, or allocates as normal if not. On the first
   * call to claim, we catch the exception that propagates out of the pool
   * and flip the boolean. Then the next call to claim must return a non-null
   * object within the test timeout.
   * If it does not, then the pool might have broken locks or it might have
   * garbage in the slot location.
   */
  @Test(timeout = 601)
  @Theory public void
  mustStillBeUsableAfterExceptionInAllocate(PoolFixture fixture)
  throws Exception {
    Allocator<GenericPoolable> allocator = new CountingAllocator() {
      final AtomicBoolean doThrow = new AtomicBoolean(true);
      @Override
      public GenericPoolable allocate(Slot slot) throws Exception {
        if (doThrow.compareAndSet(true, false)) {
          throw new RuntimeException("boo");
        }
        return super.allocate(slot);
      }
    };
    Pool<GenericPoolable> pool = fixture.initPool(config.setAllocator(allocator));
    try {
      pool.claim(longTimeout);
      fail("claim should have thrown");
    } catch (PoolException _) {}
    assertThat(pool.claim(longTimeout), is(notNullValue()));
  }

  /**
   * Likewise as above, a pool must not break its internal invariants if a
   * Reallocator throws an exception in reallocate, and it must still be
   * usable after the exception has bubbled out.
   * We test for this by configuring a Reallocator that always throws on
   * reallocate, and we also configure an Expiration that will mark the first
   * slot it checks as expired. Then, when we call claim, that first live slot
   * will be sent back for reallocation, which will throw and poison the slot.
   * Then the slot comes back to our still on-going claim, which throws
   * because of the poison. The slot then gets sent back again, and now,
   * because of the poison, it will not be reallocated, but instead have a
   * fresh Poolable allocated anew. This new good Poolable is what we get out
   * of the second call to claim.
   */
  @Test(timeout = 601)
  @Theory public void
  mustStillBeUsableAfterExceptionInReallocate(PoolFixture fixture)
      throws Exception {
    Reallocator<GenericPoolable> allocator = new CountingReallocator() {
      @Override
      public GenericPoolable reallocate(Slot slot, GenericPoolable poolable) throws Exception {
        throw new RuntimeException("boo");
      }
    };
    Expiration<Poolable> expiration = new CountingExpiration(true, false);
    Pool<GenericPoolable> pool = fixture.initPool(
        config.setAllocator(allocator).setExpiration(expiration));
    try {
      pool.claim(longTimeout);
      fail("claim should have thrown");
    } catch (PoolException _) {}
    assertThat(pool.claim(longTimeout), is(notNullValue()));
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
  @Test(timeout = 601)
  @Theory public void
  mustSwallowExceptionsFromDeallocateThroughRelease(PoolFixture fixture)
  throws Exception {
    Allocator<GenericPoolable> allocator = new CountingAllocator() {
      @Override
      public void deallocate(GenericPoolable poolable) {
        throw new RuntimeException("boo");
      }
    };
    config.setAllocator(allocator);
    Pool<GenericPoolable> pool = fixture.initPool(
        config.setExpiration(oneMsTTL));
    pool.claim(longTimeout).release();
    spinwait(2);
    pool.claim(longTimeout).release();
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
  @Test(timeout = 601)
  @Theory public void
  mustSwallowExceptionsFromDeallocateThroughShutdown(PoolFixture fixture)
  throws Exception {
    CountingAllocator allocator = new CountingAllocator() {
      @Override
      public void deallocate(GenericPoolable poolable) throws Exception {
        super.deallocate(poolable);
        throw new RuntimeException("boo");
      }
    };
    config.setAllocator(allocator).setSize(2);
    LifecycledPool<GenericPoolable> pool = lifecycled(fixture);
    Poolable obj= pool.claim(longTimeout);
    pool.claim(longTimeout).release();
    obj.release();
    pool.shutdown().await(longTimeout);
    assertThat(allocator.deallocations(), is(2));
  }
  
  /**
   * Calling await on a completion when your thread is interrupted, must
   * throw an InterruptedException.
   * In this particular case we make sure that the shut down procedure has
   * not yet completed, by claiming an object from the pool without releasing
   * it.
   */
  @Test(timeout = 601, expected = InterruptedException.class)
  @Theory public void
  awaitOnCompletionWhenInterruptedMustThrow(PoolFixture fixture)
  throws Exception {
    Completion completion = givenUnfineshedCompletion(fixture);
    Thread.currentThread().interrupt();
    completion.await(longTimeout);
  }

  private Completion givenUnfineshedCompletion(PoolFixture fixture)
  throws Exception {
    LifecycledPool<GenericPoolable> pool = lifecycled(fixture);
    assertNotNull("Did not deplete pool in time", pool.claim(longTimeout));
    return pool.shutdown();
  }
  
  /**
   * A thread that is awaiting the completion of a shut down procedure with
   * a timeout, must throw an InterruptedException if it is interrupted.
   * We test this the same way we test without the timeout. The only difference
   * is that our thread will enter the TIMED_WAITING state because of the
   * timeout.
   */
  @Test(timeout = 601, expected = InterruptedException.class)
  @Theory public void
  awaitWithTimeoutOnCompletionMustThrowUponInterruption(PoolFixture fixture)
  throws Exception {
    Completion completion = givenUnfineshedCompletion(fixture);
    forkFuture($interruptUponState(
        Thread.currentThread(), Thread.State.TIMED_WAITING));
    completion.await(longTimeout);
  }
  
  /**
   * As per the contract of throwing an InterruptedException, if the
   * await of an unfinished completion throws an InterruptedException, then
   * they must also clear the interrupted status.
   */
  @Test(timeout = 601)
  @Theory public void
  awaitOnCompletionWhenInterruptedMustClearInterruption(PoolFixture fixture)
  throws Exception {
    try {
      awaitOnCompletionWhenInterruptedMustThrow(fixture);
    } catch (InterruptedException _) {}
    assertFalse(Thread.interrupted());
    
    try {
      awaitWithTimeoutOnCompletionMustThrowUponInterruption(fixture);
    } catch (InterruptedException _) {}
    assertFalse(Thread.interrupted());
  }

  private Completion givenFinishedInterruptedCompletion(PoolFixture fixture)
      throws InterruptedException {
    LifecycledPool<GenericPoolable> pool = lifecycled(fixture);
    pool.shutdown().await(longTimeout);
    Thread.currentThread().interrupt();
    return pool.shutdown();
  }
  
  /**
   * Calling await with a timeout on a finished completion when your thread
   * is interrupted must, just as with calling await without a timeout,
   * throw an InterruptedException.
   */
  @Test(timeout = 601, expected = InterruptedException.class)
  @Theory public void
  awaitWithTimeoutOnFinishedCompletionWhenInterruptedMustThrow(
      PoolFixture fixture) throws InterruptedException {
    givenFinishedInterruptedCompletion(fixture).await(longTimeout);
  }
  
  /**
   * As per the contract of throwing an InterruptedException, the above must
   * also clear the threads interrupted status.
   */
  @Test(timeout = 601)
  @Theory public void
  awaitOnFinishedCompletionMustClearInterruption(PoolFixture fixture) {
    try {
      awaitWithTimeoutOnFinishedCompletionWhenInterruptedMustThrow(fixture);
    } catch (InterruptedException _) {}
    assertFalse(Thread.interrupted());
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
  @Test(timeout = 601, expected = PoolException.class)
  @Theory public void
  claimMustThrowIfAllocationReturnsNull(PoolFixture fixture)
  throws Exception {
    Allocator<GenericPoolable> allocator = new CountingAllocator() {
      @Override
      public GenericPoolable allocate(Slot slot) {
        return null;
      }
    };
    Pool<GenericPoolable> pool = fixture.initPool(config.setAllocator(allocator));
    pool.claim(longTimeout);
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
  @Test(timeout = 601, expected = PoolException.class)
  @Theory public void
  claimMustThrowIfReallocationReturnsNull(PoolFixture fixture)
      throws Exception {
    Allocator<GenericPoolable> allocator = new CountingReallocator() {
      @Override
      public GenericPoolable reallocate(Slot slot, GenericPoolable poolable) {
        return null;
      }
    };
    Expiration<Poolable> expiration = new CountingExpiration(true, false);
    Pool<GenericPoolable> pool = fixture.initPool(
        config.setAllocator(allocator).setExpiration(expiration));
    pool.claim(longTimeout);
  }
  
  /**
   * Threads that are already interrupted upon entry to the claim method, must
   * promptly be met with an InterruptedException. This behaviour matches that
   * of other interruptible methods in java.util.concurrent.
   * @see Pool
   */
  @Test(timeout = 601, expected = InterruptedException.class)
  @Theory public void
  claimWhenInterruptedMustThrow(PoolFixture fixture)
  throws Exception {
    Pool<GenericPoolable> pool = fixture.initPool(config);
    Thread.currentThread().interrupt();
    pool.claim(longTimeout);
  }
  
  /**
   * The claim methods checks whether the current thread is interrupted upon
   * entry, but perhaps what is more important is the interruption of a claim
   * call that is already waiting when the thread is interrupted.
   * We test for this by setting a thread to interrupt us, when our thread
   * enters the WAITING or TIMED_WAITING states. Then we make a call to the
   * appropriate claim method. If it throws an InterruptException, then the
   * test passes.
   */
  @Test(timeout = 601, expected = InterruptedException.class)
  @Theory public void
  blockedClaimWithTimeoutMustThrowUponInterruption(PoolFixture fixture)
  throws Exception {
    Pool<GenericPoolable> pool = fixture.initPool(config);
    assertNotNull("Did not deplete pool in time", pool.claim(longTimeout));
    forkFuture($interruptUponState(
        Thread.currentThread(), Thread.State.TIMED_WAITING));
    pool.claim(longTimeout);
  }

  /**
   * As per the general contract of interruptible methods, throwing an
   * InterruptedException will clear the interrupted flag on the thread.
   * This must also hold for the claim methods.
   */
  @Test(timeout = 601)
  @Theory public void
  throwingInterruptedExceptionFromClaimMustClearInterruptedFlag(
      PoolFixture fixture) throws Exception {
    try {
      blockedClaimWithTimeoutMustThrowUponInterruption(fixture);
      fail("expected InterruptedException from claim");
    } catch (InterruptedException _) {}
    assertFalse(Thread.interrupted());
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
  @Test(timeout = 601)
  @Theory public void
  claimMustStayWithinDeadlineEvenIfAllocatorBlocks(PoolFixture fixture)
  throws Exception {
    Allocator<GenericPoolable> allocator = new CountingAllocator() {
      @Override
      public GenericPoolable allocate(Slot slot) throws Exception {
        LockSupport.park();
        return super.allocate(slot);
      }
    };
    Pool<GenericPoolable> pool = fixture.initPool(config.setAllocator(allocator));
    pool.claim(shortTimeout);
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
   * 
   * We test for this by depleting a big pool with a very short TTL. After the
   * pool has been depleted, the allocator will no longer create any more
   * objects, so no expired objects will be renewed. Then we try to claim one
   * more object, and that will block. Meanwhile, another thread will perform
   * a trickle of releases. No useful objects will come back to the pool, but
   * there will still be some amount of activity. While all this goes on, the
   * blocked claim call must adhere to its specified timeout.
   * try to claim one more object
   */
  @Test(timeout = 601)
  @Theory public void
  claimMustStayWithinTimeoutEvenIfExpiredObjectIsReleased(PoolFixture fixture)
  throws Exception {
    // NOTE: This test is a little slow and may hit the 601 ms timeout even
    // if it was actually supposed to pass. Try running it again if there are
    // any problems. I may have to revisit this one in the future.
    final Poolable[] objs = new Poolable[31];
    final Lock lock = new ReentrantLock();
    Allocator<GenericPoolable> allocator = new CountingAllocator() {
      @Override
      public GenericPoolable allocate(Slot slot) throws Exception {
        lock.lock();
        try {
          return super.allocate(slot);
        } finally {
          lock.unlock();
        }
      }
    };
    config.setAllocator(allocator);
    config.setExpiration(fiveMsTTL);
    config.setSize(objs.length);
    Pool<GenericPoolable> pool = fixture.initPool(config);
    for (int i = 0; i < objs.length; i++) {
      objs[i] = pool.claim(longTimeout);
      assertNotNull("Did not claim an object in time", objs[i]);
    }
    lock.lock(); // prevent new allocations
    forkFuture($delayedReleases(objs, 10, TimeUnit.MILLISECONDS));
    // must return before test times out:
    pool.claim(new Timeout(50, TimeUnit.MILLISECONDS));
  }
  
  /**
   * When claim is called with a timeout less than one, then it means that
   * no (observable amount of) waiting should take place.
   * <p>
   * We test for this by going through the numbers 0 to 99, both inclusive,
   * and call claim with those numbers as timeout values. The test is
   * considered to have passed, if this process completes within the 601
   * millisecond timeout on the test case.
   * @see Pool#claim(Timeout)
   */
  @Test(timeout = 601)
  @Theory public void
  claimWithTimeoutValueLessThanOneMustReturnImmediately(PoolFixture fixture)
  throws Exception {
    Pool<GenericPoolable> pool = fixture.initPool(config);
    assertNotNull("Did not deplete pool in time", pool.claim(longTimeout));
    pool.claim(zeroTimeout);
  }
  
  /**
   * When await is called on a shutdown Completion with a timeout value less
   * than one, then no amount of waiting must take place.
   * <p>
   * We test for this by depleting a pool and initiating the shut-down
   * procedure. The fact that the pool is depleted, means that the shutdown
   * procedure will not be able to finish. In other words, we know that it is
   * in progress for our subsequent await calls.
   * Then we call await on the shut-down Completion object, giving timeout
   * values ranging from zero to -99. These awaits must all complete before the
   * test itself times out.
   * @see Completion
   */
  @Test(timeout = 601)
  @Theory public void
  awaitCompletionWithTimeoutLessThanOneMustReturnImmediately(
      PoolFixture fixture) throws Exception {
    LifecycledPool<GenericPoolable> pool = lifecycled(fixture);
    assertNotNull("Did not deplete pool in time", pool.claim(longTimeout));
    pool.shutdown().await(zeroTimeout);
  }
  
  /**
   * One must provide a Timeout argument when awaiting the completion of a
   * shut-down procedure. Passing null is thus an illegal argument.
   * @see Completion
   */
  @Test(timeout = 601, expected = IllegalArgumentException.class)
  @Theory public void
  awaitCompletionWithNullTimeUnitMustThrow(PoolFixture fixture)
  throws Exception {
    LifecycledPool<GenericPoolable> pool = lifecycled(fixture);
    pool.shutdown().await(null);
  }
  
  /**
   * Even if the Allocator has never made a successful allocation, the pool
   * must still be able to complete its shut-down procedure.
   * In this case we test with an allocator that always returns null.
   */
  @Test(timeout = 601)
  @Theory public void
  mustCompleteShutDownEvenIfAllSlotsHaveNullErrors(PoolFixture fixture)
  throws InterruptedException {
    Allocator<GenericPoolable> allocator = new CountingAllocator() {
      @Override
      public GenericPoolable allocate(Slot slot) throws Exception {
        return null;
      }
    };
    LifecycledPool<GenericPoolable> pool = givenPoolWithFailedAllocation(fixture, allocator);
    // the shut-down procedure must complete before the test times out.
    pool.shutdown().await(longTimeout);
  }

  private LifecycledPool<GenericPoolable> givenPoolWithFailedAllocation(
      PoolFixture fixture, Allocator<GenericPoolable> allocator) {
    config.setAllocator(allocator);
    LifecycledPool<GenericPoolable> pool = lifecycled(fixture);
    try {
      // ensure at least one allocation attempt has taken place
      pool.claim(longTimeout);
      fail("allocation attempt should have failed!");
    } catch (Exception _) {
      // we don't care about this one
    }
    return pool;
  }
  
  /**
   * As with
   * {@link #mustCompleteShutDownEvenIfAllSlotsHaveNullErrors(PoolFixture)},
   * the pool must be able to shut down if it has never been able to allocate
   * anything.
   * In this case we test with an allocator that always throws an exception.
   */
  @Test(timeout = 601)
  @Theory public void
  mustCompleteShutDownEvenIfAllSlotsHaveAllocationErrors(PoolFixture fixture)
  throws InterruptedException {
    Allocator<GenericPoolable> allocator = new CountingAllocator() {
      @Override
      public GenericPoolable allocate(Slot slot) throws Exception {
        throw new Exception("it's terrible stuff!!!");
      }
    };
    LifecycledPool<GenericPoolable> pool = givenPoolWithFailedAllocation(fixture, allocator);
    // must complete before the test timeout:
    pool.shutdown().await(longTimeout);
  }
  
  /**
   * Calling shutdown on a pool while being interrupted must still start the
   * shut-down procedure.
   * We test for this by initiating the shut-down procedure on a pool while
   * being interrupted. Then we clear our interrupted flag and await the
   * completion of the shut-down procedure. The procedure must complete within
   * the test timeout. If it does not, then that is taken as evidence that
   * the procedure did NOT start, and so the test fails.
   * @see Pool
   */
  @Test(timeout = 601)
  @Theory public void
  mustBeAbleToShutDownEvenIfInterrupted(PoolFixture fixture)
  throws InterruptedException {
    LifecycledPool<GenericPoolable> pool = lifecycled(fixture);
    Thread.currentThread().interrupt();
    Completion completion = pool.shutdown();
    Thread.interrupted(); // clear interrupted flag
    completion.await(longTimeout); // must complete before test timeout
  }
  
  /**
   * Initiating the shut-down procedure must not influence the threads
   * interruption status.
   * We test for this by calling shutdown on the pool while being interrupted.
   * Then we check that we are still interrupted.
   * @see Pool
   */
  @Test(timeout = 601)
  @Theory public void
  callingShutdownMustNotAffectInterruptionStatus(PoolFixture fixture)
  throws InterruptedException {
    LifecycledPool<GenericPoolable> pool = lifecycled(fixture);
    Thread.currentThread().interrupt();
    pool.shutdown();
    assertTrue(Thread.interrupted());
  }
  
  /**
   * Pool implementations might do some kind of biasing to reduce contention.
   * We need to make sure that if there are not enough objects for all the
   * threads, then even biased objects must participate in the circulation.
   * If they don't, then some threads might starve.
   */
  @Test(timeout = 601)
  @Theory public void
  mustDebiasObjectsNoLongerClaimed(PoolFixture fixture) throws Exception {
    Pool<GenericPoolable> pool = fixture.initPool(config);
    Poolable obj = pool.claim(longTimeout);
    obj.release(); // item now biased to our thread
    // claiming in a different thread should give us the same object.
    AtomicReference<GenericPoolable> ref =
        new AtomicReference<GenericPoolable>();
    join(forkFuture(capture($claim(pool, longTimeout), ref)));
    assertThat(ref.get(), is(obj));
  }
  
  /**
   * Pool implementations that do biasing need to ensure, that claimed objects
   * are not available for other threads, even if the object is claimed with
   * a biased kind of claim.
   * In other words, if we claim an object, it might become biased to us. If we
   * then release it and then call claim again, we might go through a different
   * path through the code. And this new path needs to ensure that the object
   * is properly claimed, such that no other threads can claim it as well.
   */
  @Test(timeout = 601)
  @Theory public void
  biasedClaimMustUpgradeToOrdinaryClaimIfTheObjectIsPulledFromTheQueue(PoolFixture fixture) throws Exception {
    Pool<GenericPoolable> pool = fixture.initPool(config);
    pool.claim(longTimeout).release(); // bias the object to our thread
    pool.claim(longTimeout); // this is now our biased claim
    AtomicReference<GenericPoolable> ref = new AtomicReference<GenericPoolable>();
    // the biased claim will be upgraded to an ordinary claim:
    join(forkFuture(capture($claim(pool, zeroTimeout), ref)));
    assertThat(ref.get(), nullValue());
  }
  
  /**
   * If a pool has been depleted, and then shut down, and another call to claim
   * comes in, then it must immediately throw an IllegalStateException.
   * Importantly, it must not block the thread to wait for any objects to be
   * released.
   */
  @Test(timeout = 601, expected = IllegalStateException.class)
  @Theory public void
  depletedPoolThatHasBeenShutDownMustThrowUponClaim(PoolFixture fixture) throws Exception {
    LifecycledPool<GenericPoolable> pool = lifecycled(fixture);
    pool.claim(longTimeout); // depleted
    pool.shutdown();
    pool.claim(longTimeout);
  }

  /**
   * Basically the same test as above, except now we wait for the shutdown
   * process to make a bit of progress. This might expose different race bugs.
   */
  @Test(timeout = 601, expected = IllegalStateException.class)
  @Theory public void
  depletedPoolThatHasBeenShutDownMustThrowUponClaimEvenAfterSomeTime(PoolFixture fixture) throws Exception {
    LifecycledPool<GenericPoolable> pool = lifecycled(fixture);
    pool.claim(longTimeout); // depleted
    pool.shutdown();
    spinwait(10);
    pool.claim(longTimeout);
  }
  
  /**
   * We must ensure that, for pool implementation that do biasing, the checking
   * of whether the pool has been shut down must come before even a biased
   * claim. Even though a biased claim might not do any waiting that a normal
   * claim might do, it is still important that the shutdown notification takes
   * precedence, because we don't know for how long the claimed object will be
   * held, or if there will be other biased claims in the future.
   */
  @Test(timeout = 601, expected = IllegalStateException.class)
  @Theory public void
  poolThatHasBeenShutDownMustThrowUponClaimEvenIfItHasAvailableNonbiasedObjects(PoolFixture fixture) throws Exception {
    config.setSize(4);
    LifecycledPool<GenericPoolable> pool = lifecycled(fixture);
    GenericPoolable a = pool.claim(longTimeout);
    GenericPoolable b = pool.claim(longTimeout);
    GenericPoolable c = pool.claim(longTimeout);
    GenericPoolable d = pool.claim(longTimeout);
    a.release(); // placed ahead of any poison pills
    pool.shutdown();
    b.release();
    c.release();
    d.release();
    pool.claim(longTimeout);
  }
  
  /**
   * It is explicitly permitted that the thread that releases an object back
   * into the pool, can be a different thread than the one that claimed the
   * particular object.
   */
  @Test(timeout = 601)
  @Theory public void
  mustNotThrowWhenReleasingObjectClaimedByAnotherThread(PoolFixture fixture) throws Exception {
    Pool<GenericPoolable> pool = fixture.initPool(config);
    GenericPoolable obj = forkFuture($claim(pool, longTimeout)).get();
    obj.release();
  }

  @Test(expected = IllegalArgumentException.class)
  @Theory public void
  targetSizeMustBeGreaterThanZero(PoolFixture fixture) {
    ResizablePool<GenericPoolable> pool = resizable(fixture);
    pool.setTargetSize(0);
  }

  @Theory public void
  targetSizeMustBeConfiguredSizeByDefault(PoolFixture fixture) {
    config.setSize(23);
    ResizablePool<GenericPoolable> pool = resizable(fixture);
    assertThat(pool.getTargetSize(), is(23));
  }

  @Theory public void
  getTargetSizeMustReturnLastSetTargetSize(PoolFixture fixture) {
    ResizablePool<GenericPoolable> pool = resizable(fixture);
    pool.setTargetSize(3);
    assertThat(pool.getTargetSize(), is(3));
  }

  /**
   * When we increase the size of a depleted pool, it should be possible to
   * make claim again and get out newly allocated objects.
   *
   * We test for this by depleting a pool, upping the size and then claiming
   * again with a timeout that is longer than the timeout of the test. The test
   * pass if it does not timeout.
   */
  @Test(timeout = 601)
  @Theory public void
  increasingSizeMustAllowMoreAllocations(PoolFixture fixture) throws Exception {
    ResizablePool<GenericPoolable> pool = resizable(fixture);
    pool.claim(longTimeout); // depleted
    pool.setTargetSize(2);
    // now this mustn't block:
    pool.claim(longTimeout);
  }

  /**
   * We must somehow ensure that the pool starts deallocating more than it
   * allocates, when the pool is shrunk. This is difficult because the pool
   * cannot tell us when it reaches the target size, so we have to figure this
   * out by using a special allocator.
   *
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
  @Test(timeout = 601)
  @Theory public void
  decreasingSizeMustEventuallyDeallocateSurplusObjects(PoolFixture fixture)
      throws Exception {
    int startingSize = 5;
    int newSize = 1;
    final Thread main = Thread.currentThread();
    CountingAllocator allocator = new UnparkingCountingAllocator(main);
    config.setSize(startingSize);
    config.setAllocator(allocator);
    ResizablePool<GenericPoolable> pool = resizable(fixture);
    List<GenericPoolable> objs = new ArrayList<GenericPoolable>();

    while (allocator.allocations() != startingSize) {
      objs.add(pool.claim(longTimeout)); // force the pool to do work
    }
    pool.setTargetSize(newSize);
    while (allocator.deallocations() != startingSize - newSize) {
      if (objs.size() > 0) {
        objs.remove(0).release(); // give the pool objects to deallocate
      } else {
        pool.claim(longTimeout).release(); // prod it & poke it
      }
      LockSupport.parkNanos(10000000); // 10 millis
    }
    assertThat(
        allocator.allocations() - allocator.deallocations(), is(newSize));
  }

  /**
   * Similar to the decreasingSizeMustEventuallyDeallocateSurplusObjects test
   * above, but this time the objects are all expired after the pool has been
   * shrunk.
   *
   * Again, we deplete the pool. Once depleted, our expiration has been
   * configured such, that all subsequent items one tries to claim, will be
   * expired.
   *
   * Then we set the new lower target size, and release just enough for the
   * pool to reach the new target.
   *
   * Then we try to claim an object from the pool with a very short timeout.
   * This will return null because the pool is still depleted. We also check
   * that the pool has not made any new allocations, even though we have been
   * releasing objects. We don't check the deallocations because it's
   * complicated and we did it in the
   * decreasingSizeMustEventuallyDeallocateSurplusObjects test above.
   */
  @Test(timeout = 601)
  @Theory public void
  mustNotReallocateWhenReleasingExpiredObjectsIntoShrunkPool(PoolFixture fixture)
      throws Exception {
    int startingSize = 5;
    int newSize = 1;
    CountingAllocator allocator = new CountingAllocator();
    Expiration<Poolable> expiration = new CountingExpiration(
        // our 5 items are not expired when we deplete the pool
        false, false, false, false, false,
        // but all items we try to claim after that *are* expired.
        true
    );
    config.setExpiration(expiration).setAllocator(allocator);
    config.setSize(startingSize);
    ResizablePool<GenericPoolable> pool = resizable(fixture);
    List<GenericPoolable> objs = new ArrayList<GenericPoolable>();
    for (int i = 0; i < startingSize; i++) {
      objs.add(pool.claim(longTimeout));
    }
    assertThat(objs.size(), is(startingSize));
    pool.setTargetSize(newSize);
    for (int i = 0; i < startingSize - newSize; i++) {
      // release the surplus expired objects back into the pool
      objs.remove(0).release();
    }
    // now the released objects should not cause reallocations, so claim
    // returns null (it's still depleted) and allocation count stays put
    assertThat(pool.claim(shortTimeout), nullValue());
    assertThat(allocator.allocations(), is(startingSize));
  }

  @Theory public void
  settingTargetSizeOnPoolThatHasBeenShutDownDoesNothing(PoolFixture fixture) {
    config.setSize(3);
    LifecycledResizablePool<GenericPoolable> pool = lifecycledResizable(fixture);
    pool.shutdown();
    pool.setTargetSize(10); // this should do nothing, because it's shut down
    assertThat(pool.getTargetSize(), is(3));
  }

  /**
   * Make sure that the pool does not get into a bad state, caused by concurrent
   * background resizing jobs interferring with each other.
   *
   * We test this by creating a small pool, then resizing it larger (so much so that
   * any resizing job is unlikely to finish before we can make our next move) and then
   * immediately resizing it smaller again. This should put multiple resizing jobs in
   * flight. When all the background jobs complete, we should observe that the pool
   * ended up with exactly the target size number of items in it.
   */
  @Test(timeout = 601)
  @Theory public void
  increasingAndDecreasingSizeInQuickSuccessionMustEventuallyReachTargetSize(
      PoolFixture fixture) throws Exception {
    ResizablePool<GenericPoolable> pool = resizable(fixture);

    // Fiddle with the target size.
    pool.setTargetSize(20);
    pool.setTargetSize(1);

    // Then wait for the size of the pool to settle
    int deallocations;
    int allocations;
    do {
      Thread.sleep(1);
      deallocations = allocator.deallocations();
      allocations = allocator.allocations();
    } while (allocations - deallocations > 1);

    // Now we should be left with exactly one object that we can claim:
    GenericPoolable obj = pool.claim(longTimeout);
    try {
      assertThat(pool.claim(shortTimeout), nullValue());
    } finally {
      obj.release();
    }
  }

  @Test(timeout = 601)
  @Theory public void
  mustNotLeakSlotsIfExpirationThrowsThrowableInsteadOfException(PoolFixture fixture) throws InterruptedException {
    final AtomicBoolean shouldThrow = new AtomicBoolean(true);
    config.setExpiration(new Expiration<GenericPoolable>() {
      @Override
      public boolean hasExpired(SlotInfo<? extends GenericPoolable> info) throws Exception {
        if (shouldThrow.get()) {
          sneakyThrow(new SomeRandomThrowable("Boom!"));
        }
        return false;
      }
    });

    LifecycledPool<GenericPoolable> pool = lifecycled(fixture);
    try {
      pool.claim(longTimeout);
      fail("Expected claim to throw");
    } catch (PoolException pe) {
      assertThat(pe.getCause(), instanceOf(SomeRandomThrowable.class));
    }

    // Now, the slot should not have leaked, so the next claim should succeed:
    shouldThrow.set(false);
    pool.claim(longTimeout).release();
    pool.shutdown();
  }

  // TODO must reallocate poisoned slots when allocator recovers


  // NOTE: When adding, removing or modifying tests, also remember to update
  //       the Pool javadoc.
}
