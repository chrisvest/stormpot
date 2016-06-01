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

import org.junit.After;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TestRule;
import org.junit.runner.RunWith;
import org.junit.runners.Parameterized;
import org.junit.runners.Parameterized.Parameters;

import javax.management.JMX;
import javax.management.MBeanServer;
import javax.management.MBeanServerFactory;
import javax.management.ObjectName;
import java.lang.ref.WeakReference;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.locks.Lock;
import java.util.concurrent.locks.LockSupport;
import java.util.concurrent.locks.ReentrantLock;
import java.util.function.Consumer;
import java.util.function.Function;

import static java.util.function.Function.identity;
import static org.hamcrest.Matchers.*;
import static org.junit.Assert.*;
import static org.junit.Assume.assumeThat;
import static stormpot.AlloKit.*;
import static stormpot.AlloKit.$countDown;
import static stormpot.AlloKit.$if;
import static stormpot.ExpireKit.*;
import static stormpot.ExpireKit.$countDown;
import static stormpot.ExpireKit.$if;
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
@RunWith(Parameterized.class)
public class PoolTest {
  private static final int TIMEOUT = 42424;
  @Rule public final TestRule failurePrinter = new FailurePrinterTestRule();
  
  private static final Expiration<Poolable> oneMsTTL =
      new TimeExpiration<>(1, TimeUnit.MILLISECONDS);
  private static final Expiration<Poolable> fiveMsTTL =
      new TimeExpiration<>(5, TimeUnit.MILLISECONDS);
  private static final Timeout longTimeout = new Timeout(5, TimeUnit.MINUTES);
  private static final Timeout mediumTimeout = new Timeout(10, TimeUnit.MILLISECONDS);
  private static final Timeout shortTimeout = new Timeout(1, TimeUnit.MILLISECONDS);
  private static final Timeout zeroTimeout = new Timeout(0, TimeUnit.MILLISECONDS);

  private final PoolFixture fixture;
  private CountingAllocator allocator;
  private Config<GenericPoolable> config;
  private Pool<GenericPoolable> pool;

  @Parameters(name = "{0}")
  public static Object[][] dataPoints() {
    return new Object[][] {
        {"blazePool", new BlazePoolFixture()},
        {"queuePool", new QueuePoolFixture()}
    };
  }

  @SuppressWarnings("UnusedParameters")
  public PoolTest(String implementationName, PoolFixture fixture) {
    this.fixture = fixture;
  }

  @Before public void
  setUp() {
    allocator = allocator();
    config = new Config<GenericPoolable>().setSize(1).setAllocator(allocator);
  }

  @After public void
  shutPoolDown() throws InterruptedException {
    if (pool != null) {
      String poolName = pool.getClass().getSimpleName();
      assertTrue(
          "Pool did not shut down within timeout: " + poolName,
          pool.shutdown().await(longTimeout));
    }
  }

  private void createPool() {
    pool = fixture.initPool(config);
  }

  private ManagedPool assumeManagedPool() {
    createPool();
    assumeThat(pool, instanceOf(ManagedPool.class));
    return (ManagedPool) pool;
  }

  @Test(expected = IllegalArgumentException.class) public void
  timeoutCannotBeNull() throws Exception {
    createPool();
    pool.claim(null);
  }
  
  /**
   * A call to claim must return before the timeout elapses if it
   * can claim an object from the pool, and it must return that object.
   * The timeout for the claim is longer than the timeout for the test, so we
   * know that we won't get a null back here because the timeout wasn't long
   * enough. If we do, then the pool does not correctly implement the timeout
   * behaviour.
   */
  @Test(timeout = TIMEOUT) public void
  claimMustReturnIfWithinTimeout() throws Exception {
    createPool();
    Poolable obj = pool.claim(longTimeout);
    try {
      assertThat(obj, not(nullValue()));
    } finally {
      obj.release();
    }
  }
  
  /**
   * A call to claim that fails to get an object before the
   * timeout elapses, must return null.
   * We test this by depleting a pool, and then make a call to claim with
   * a shot timeout. If that call returns <code>null</code>, then we're good.
   */
  @Test(timeout = TIMEOUT) public void
  claimMustReturnNullIfTimeoutElapses() throws Exception {
    createPool();
    GenericPoolable a = pool.claim(longTimeout); // pool is now depleted
    Poolable b = pool.claim(shortTimeout);
    try {
      assertThat(b, is(nullValue()));
    } finally {
      a.release();
    }
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
  @Test(timeout = TIMEOUT) public void
  mustGetPooledObjectsFromAllocator() throws Exception {
    createPool();
    GenericPoolable obj = pool.claim(longTimeout);
    try {
      assertThat(allocator.countAllocations(), is(greaterThan(0)));
    } finally {
      obj.release();
    }
  }
  
  /**
   * If the pool has been depleted for objects, then a call to claim with
   * timeout will wait until either an object becomes available, or the timeout
   * elapses. Whichever comes first.
   * We test for this by observing that a thread that makes a claim-with-timeout
   * call to a depleted pool, will enter the TIMED_WAITING state.
   */
  @Test(timeout = TIMEOUT) public void
  blockingClaimWithTimeoutMustWaitIfPoolIsEmpty()
      throws Exception {
    createPool();
    GenericPoolable obj = pool.claim(longTimeout);
    assertNotNull("Did not deplete pool in time", obj);
    AtomicReference<GenericPoolable> ref = new AtomicReference<>();
    Thread thread = fork(capture($claim(pool, longTimeout), ref));
    try {
      waitForThreadState(thread, Thread.State.TIMED_WAITING);
    } finally {
      obj.release();
      thread.join();
      ref.get().release();
    }
  }

  /**
   * A thread that is waiting in claim-with-timeout on a depleted pool must
   * wake up if another thread releases an object back into the pool.
   * So if we deplete a pool, make a thread wait in claim-with-timeout and
   * then release an object back into the pool, then we must be able to join
   * to that thread.
   */
  @Test(timeout = TIMEOUT) public void
  blockingOnClaimWithTimeoutMustResumeWhenPoolablesAreReleased()
      throws Exception {
    createPool();
    Poolable obj = pool.claim(longTimeout);
    assertNotNull("Did not deplete pool in time", obj);
    AtomicReference<GenericPoolable> ref = new AtomicReference<>();
    Thread thread = fork(capture($claim(pool, longTimeout), ref));
    waitForThreadState(thread, Thread.State.TIMED_WAITING);
    obj.release();
    join(thread);
    ref.get().release();
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
  @Test(timeout = TIMEOUT) public void
  mustReuseAllocatedObjects() throws Exception {
    createPool();
    pool.claim(longTimeout).release();
    pool.claim(longTimeout).release();
    assertThat(allocator.countAllocations(), is(1));
  }
  
  /**
   * Be careful and prevent the creation of pools with a size less than one.
   * The contract of claim is to block indefinitely if one such pool were
   * to be created.
   * @see Config#setSize(int)
   */
  @Test(timeout = TIMEOUT, expected = IllegalArgumentException.class) public void
  constructorMustThrowOnPoolSizeLessThanOne() {
    fixture.initPool(config.setSize(0));
  }

  /**
   * Prevent the creation of pools with a null Expiration.
   * @see Config#setExpiration(Expiration)
   */
  @Test(timeout = TIMEOUT, expected = IllegalArgumentException.class) public void
  constructorMustThrowOnNullExpiration() {
    fixture.initPool(config.setExpiration(null));
  }
  
  /**
   * Prevent the creation of pools with a null Allocator.
   * @see Config#setAllocator(Allocator)
   */
  @Test(timeout = TIMEOUT, expected = IllegalArgumentException.class) public void
  constructorMustThrowOnNullAllocator() {
    fixture.initPool(config.setAllocator(null));
  }

  /**
   * Prevent the creation of pools with a null ThreadFactory.
   * @see Config#setThreadFactory(java.util.concurrent.ThreadFactory)
   */
  @Test(timeout = TIMEOUT, expected = IllegalArgumentException.class) public void
  constructorMustThrowOnNullThreadFactory() {
    fixture.initPool(config.setThreadFactory(null));
  }

  @Test(timeout = TIMEOUT, expected = NullPointerException.class) public void
  constructorMustThrowIfConfiguredThreadFactoryReturnsNull() {
    ThreadFactory factory = r -> null;
    config.setThreadFactory(factory);
    createPool();
  }
  
  /**
   * Pools must use the provided expiration to determine whether slots
   * are invalid or not, instead of using their own ad-hoc mechanisms.
   * We test this by using an expiration that counts the number of times
   * it is invoked. Then we claim an object and assert that the expiration
   * was invoked at least once, presumably for that object.
   */
  @Test(timeout = TIMEOUT) public void
  mustUseProvidedExpiration() throws Exception {
    config.setAllocator(allocator());
    CountingExpiration expiration = expire($fresh);
    config.setExpiration(expiration);
    createPool();
    pool.claim(longTimeout).release();
    assertThat(expiration.countExpirations(), greaterThanOrEqualTo(1));
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
  @Test(timeout = TIMEOUT, expected = SomeRandomException.class) public void
  exceptionsFromExpirationMustBubbleOut() throws Throwable {
    config.setExpiration(expire($throwExpire(new SomeRandomException())));
    createPool();

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
  @Test(timeout = TIMEOUT) public void
  slotsThatMakeTheExpirationThrowAreInvalid() throws Exception {
    config.setExpiration(expire($throwExpire(new SomeRandomException())));
    createPool();
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
    assertThat(allocator.countDeallocations(), greaterThanOrEqualTo(1));
  }
  
  /**
   * SlotInfo objects offer a count of how many times the Poolable it
   * represents, has been claimed. Naturally, this count must increase every
   * time that object is claimed.
   * We test for this by creating an Expiration that writes the count to
   * an atomic every time it is called. Then we make a couple of claims and
   * releases, and assert that the recorded count has gone up.
   */
  @Test(timeout = TIMEOUT) public void
  slotInfoClaimCountMustIncreaseWithClaims() throws Exception {
    final AtomicLong claims = new AtomicLong();
    config.setExpiration(expire($capture($claimCount(claims), $fresh)));
    createPool();
    pool.claim(longTimeout).release();
    pool.claim(longTimeout).release();
    pool.claim(longTimeout).release();
    // We have made claims, and the expiration ought to have noted this.
    // We should observe a claim-count of 2, rather than 3, because the
    // expiration only gets to see past claims, not the one that is being
    // processed at the time the expiration check happens.
    assertThat(claims.get(), is(2L));
  }
  
  /**
   * Expirations might require access to the actual object in question
   * being pooled, in order to implement advanced and/or domain specific
   * logic.
   * As with the claim count test above, we configure an expiration
   * that puts the value into an atomic. Then we assert that the value of the
   * atomic is one of the claimed objects.
   */
  @Test(timeout = TIMEOUT) public void
  slotInfoMustHaveReferenceToItsPoolable() throws Exception {
    final AtomicReference<Poolable> lastPoolable = new AtomicReference<>();
    config.setExpiration(expire($capture($poolable(lastPoolable), $fresh)));
    createPool();
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
  @Test(timeout = TIMEOUT) public void
  slotInfoMustBeAbleToProduceRandomNumbers() throws Exception {
    final AtomicReference<SlotInfo<? extends Poolable>> slotInfoRef =
        new AtomicReference<>();
    config.setExpiration(expire($capture($slotInfo(slotInfoRef), $fresh)));
    createPool();
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
  @Test(timeout = TIMEOUT) public void
  slotInfoClaimCountMustResetIfSlotsAreReused() throws Exception {
    final AtomicLong maxClaimCount = new AtomicLong();
    Expiration<Poolable> expiration = info -> {
      maxClaimCount.set(Math.max(maxClaimCount.get(), info.getClaimCount()));
      return info.getClaimCount() > 1;
    };
    config.setExpiration(expiration);
    createPool();
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
  @Test(timeout = TIMEOUT) public void
  slotInfoMustRememberStamp() throws Exception {
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
    config.setExpiration(expiration);
    createPool();
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
  @Test(timeout = TIMEOUT) public void
  slotInfoStampMustResetIfSlotsAreReused() throws Exception {
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
    config.setExpiration(expiration);
    createPool();

    pool.claim(longTimeout).release();
    pool.claim(longTimeout).release();
    pool.claim(longTimeout).release();
    
    assertThat(zeroStampsCounted.get(), is(3L));
  }

  @Test(timeout = TIMEOUT) public void
  slotInfoMustHaveAgeInMillis() throws InterruptedException {
    final AtomicLong age = new AtomicLong();
    config.setExpiration(expire($capture($age(age), $fresh)));
    createPool();
    pool.claim(longTimeout).release();
    long firstAge = age.get();
    spinwait(5);
    pool.claim(longTimeout).release();
    long secondAge = age.get();
    assertThat(secondAge - firstAge, greaterThanOrEqualTo(5L));
  }

  @Test(timeout = TIMEOUT) public void
  slotInfoAgeMustResetAfterAllocation() throws InterruptedException {
    final AtomicBoolean hasExpired = new AtomicBoolean();
    final AtomicLong age = new AtomicLong();
    config.setExpiration(expire(
        $capture($age(age), $expiredIf(hasExpired))));
    // Reallocations will fail, causing the slot to be poisoned.
    // Then, the poisoned slot will not be reallocated again, but rather
    // go through the deallocate-allocate cycle.
    config.setAllocator(reallocator(realloc($throw(new Exception()))));
    createPool();
    pool.claim(longTimeout).release();
    Thread.sleep(100); // time transpires
    pool.claim(longTimeout).release();
    long firstAge = age.get(); // age is now at least 5 ms
    hasExpired.set(true);
    try {
      pool.claim(longTimeout).release();
    } catch (Exception e) {
      // ignore
    }
    hasExpired.set(false);
    // new object should have a new age
    pool.claim(longTimeout).release();
    long secondAge = age.get(); // age should be less than age of prev. obj.
    assertThat(secondAge, lessThan(firstAge));
  }

  @Test(timeout = TIMEOUT) public void
  slotInfoAgeMustResetAfterReallocation() throws InterruptedException {
    final AtomicBoolean hasExpired = new AtomicBoolean();
    final AtomicLong age = new AtomicLong();
    config.setExpiration(expire(
        $capture($age(age), $expiredIf(hasExpired))));
    createPool();
    pool.claim(longTimeout).release();
    Thread.sleep(100); // time transpires
    pool.claim(longTimeout).release();
    long firstAge = age.get();
    hasExpired.set(true);
    assertNull(pool.claim(zeroTimeout)); // cause reallocation
    hasExpired.set(false);
    pool.claim(longTimeout).release(); // new object, new age
    long secondAge = age.get();
    assertThat(secondAge, lessThan(firstAge));
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
  @Test(timeout = TIMEOUT, expected = IllegalStateException.class) public void
  preventClaimFromPoolThatIsShutDown() throws Exception {
    createPool();
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
  @Test(timeout = TIMEOUT) public void
  mustReplaceExpiredPoolables() throws Exception {
    config.setExpiration(oneMsTTL);
    createPool();
    pool.claim(longTimeout).release();
    spinwait(2);
    pool.claim(longTimeout).release();
    assertThat(allocator.countAllocations(), greaterThanOrEqualTo(2));
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
  @Test(timeout = TIMEOUT) public void
  mustDeallocateExpiredPoolablesAndStayWithinSizeLimit() throws Exception {
    config.setExpiration(oneMsTTL);
    createPool();
    pool.claim(longTimeout).release();
    spinwait(2);
    pool.claim(longTimeout).release();
    assertThat(allocator.countDeallocations(), greaterThanOrEqualTo(1));
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
  @Test(timeout = TIMEOUT) public void
  mustDeallocateAllPoolablesBeforeShutdownTaskReturns() throws Exception {
    config.setSize(2);
    createPool();
    Poolable p1 = pool.claim(longTimeout);
    Poolable p2 = pool.claim(longTimeout);
    p1.release();
    p2.release();
    pool.shutdown().await(longTimeout);
    assertThat(allocator.countDeallocations(), is(2));
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
  @Test(timeout = TIMEOUT) public void
  shutdownCallMustReturnFastIfPoolablesAreStillClaimed() throws Exception {
    createPool();
    GenericPoolable obj = pool.claim(longTimeout);
    try {
      pool.shutdown();
      assertNotNull("Did not deplete pool in time", obj);
    } finally {
      obj.release();
    }
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
  @Test(timeout = TIMEOUT) public void
  shutdownMustNotDeallocateClaimedPoolables() throws Exception {
    createPool();
    GenericPoolable obj = pool.claim(longTimeout);
    assertNotNull("Did not deplete pool in time", obj);
    pool.shutdown().await(mediumTimeout);
    try {
      assertThat(allocator.countDeallocations(), is(0));
    } finally {
      obj.release();
    }
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
  @Test(timeout = TIMEOUT) public void
  awaitOnShutdownMustReturnWhenClaimedObjectsAreReleased() throws Exception {
    createPool();
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
  @Test(timeout = TIMEOUT) public void
  awaitWithTimeoutMustReturnFalseIfTimeoutElapses() throws Exception {
    createPool();
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
  @Test(timeout = TIMEOUT) public void
  awaitWithTimeoutMustReturnTrueIfCompletesWithinTimeout() throws Exception {
    createPool();
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
  @Test(timeout = TIMEOUT) public void
  awaitingOnAlreadyCompletedShutDownMustNotBlock() throws Exception {
    createPool();
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
  @Test(timeout = TIMEOUT) public void
  blockedClaimMustThrowWhenPoolIsShutDown() throws Exception {
    createPool();
    AtomicReference<Exception> caught = new AtomicReference<>();
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
  @Test(timeout = TIMEOUT) public void
  mustNotDeallocateTheSameObjectMoreThanOnce() throws Exception {
    config.setExpiration(oneMsTTL);
    createPool();
    Poolable obj = pool.claim(longTimeout);
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
    pool.claim(longTimeout).release();
    // check if the deallocation list contains duplicates
    List<GenericPoolable> deallocations = allocator.getDeallocations();
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
  @Test(timeout = TIMEOUT) public void
  shutdownMustNotDeallocateEmptySlots() throws Exception {
    final AtomicBoolean wasNull = new AtomicBoolean();
    allocator = allocator(dealloc($observeNull(wasNull, $null)));
    config.setAllocator(allocator).setExpiration(oneMsTTL);
    createPool();
    pool.claim(longTimeout).release();
    pool.shutdown().await(longTimeout);
    assertFalse(wasNull.get());
  }

  @Test(timeout = TIMEOUT) public void
  shutdownMustEventuallyDeallocateAllPoolables() throws Exception {
    int size = 10;
    config.setSize(size);
    createPool();
    List<GenericPoolable> objs = new ArrayList<>();
    for (int i = 0; i < size; i++) {
      objs.add(pool.claim(longTimeout));
    }
    Completion completion = pool.shutdown();
    completion.await(shortTimeout);
    for (GenericPoolable obj : objs) {
      obj.release();
    }
    completion.await(longTimeout);
    assertThat(allocator.countDeallocations(), is(size));
  }
  
  /**
   * Pools must be prepared in the event that an Allocator throws an
   * Exception from allocate. If it is not possible to allocate an object,
   * then the pool must throw a PoolException through claim.
   * @see PoolException
   */
  @Test(timeout = TIMEOUT) public void
  mustPropagateExceptionsFromAllocateThroughClaim() throws Exception {
    final RuntimeException expectedException = new RuntimeException("boo");
    allocator = allocator(alloc($throw(expectedException)));
    config.setAllocator(allocator);
    createPool();
    try {
      pool.claim(longTimeout);
      fail("expected claim to throw");
    } catch (PoolException poolException) {
      assertThat(poolException.getCause(), is((Throwable) expectedException));
    }
  }

  /**
   * Pools must be prepared in the event that a Reallocator throws an
   * Exception from reallocate. If it is not possible to reallocate an object,
   * then the pool must throw a PoolException through claim.
   * @see PoolException
   */
  @Test(timeout = TIMEOUT) public void
  mustPropagateExceptionsFromReallocateThroughClaim() throws Exception {
    final RuntimeException expectedException = new RuntimeException("boo");
    AtomicBoolean hasExpired = new AtomicBoolean();
    config.setAllocator(reallocator(realloc($throw(expectedException))));
    config.setExpiration(expire($expiredIf(hasExpired)));
    config.setSize(2);
    createPool();
    // Make sure the pool is fully allocated
    hasExpired.set(false);
    GenericPoolable obj1 = pool.claim(longTimeout);
    GenericPoolable obj2 = pool.claim(longTimeout);
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
  @Test(timeout = TIMEOUT) public void
  mustStillBeUsableAfterExceptionInAllocate() throws Exception {
    allocator = allocator(alloc($throw(new RuntimeException("boo")), $new));
    config.setAllocator(allocator);
    createPool();
    try {
      pool.claim(longTimeout).release();
    } catch (PoolException ignore) {}
    GenericPoolable obj = pool.claim(longTimeout);
    assertThat(obj, is(notNullValue()));
    obj.release();
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
   * of the last call to claim.
   */
  @Test(timeout = TIMEOUT) public void
  mustStillBeUsableAfterExceptionInReallocate() throws Exception {
    final AtomicBoolean throwInAllocate = new AtomicBoolean();
    final AtomicBoolean hasExpired = new AtomicBoolean();
    final CountDownLatch allocationLatch = new CountDownLatch(2);
    config.setAllocator(reallocator(
        alloc($if(throwInAllocate,
            $throw(new RuntimeException("boo")),
            $countDown(allocationLatch, $new))),
        realloc($throw(new RuntimeException("boo")))));
    config.setExpiration(expire($expiredIf(hasExpired)));
    createPool();
    pool.claim(longTimeout).release(); // object now allocated
    throwInAllocate.set(true);
    hasExpired.set(true);
    try {
      pool.claim(longTimeout);
      fail("claim should have thrown");
    } catch (PoolException ignore) {}
    throwInAllocate.set(false);
    hasExpired.set(false);
    allocationLatch.await();
    GenericPoolable claim = pool.claim(longTimeout);
    assertThat(claim, is(notNullValue()));
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
  @Test(timeout = TIMEOUT) public void
  mustSwallowExceptionsFromDeallocateThroughRelease() throws Exception {
    allocator = allocator(dealloc($throw(new RuntimeException("boo"))));
    config.setAllocator(allocator);
    config.setExpiration(oneMsTTL);
    createPool();
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
  @Test(timeout = TIMEOUT) public void
  mustSwallowExceptionsFromDeallocateThroughShutdown() throws Exception {
    CountingAllocator allocator = allocator(
        dealloc($throw(new RuntimeException("boo"))));
    config.setAllocator(allocator).setSize(2);
    createPool();
    Poolable obj= pool.claim(longTimeout);
    pool.claim(longTimeout).release();
    obj.release();
    pool.shutdown().await(longTimeout);
    assertThat(allocator.countDeallocations(), is(2));
  }
  
  /**
   * Calling await on a completion when your thread is interrupted, must
   * throw an InterruptedException.
   * In this particular case we make sure that the shut down procedure has
   * not yet completed, by claiming an object from the pool without releasing
   * it.
   */
  @Test(timeout = TIMEOUT, expected = InterruptedException.class) public void
  awaitOnCompletionWhenInterruptedMustThrow() throws Exception {
    createPool();
    GenericPoolable obj = pool.claim(longTimeout);
    assertNotNull("Did not deplete pool in time", obj);
    Completion completion = pool.shutdown();
    Thread.currentThread().interrupt();
    try {
      completion.await(longTimeout);
    } finally {
      obj.release();
    }
  }

  /**
   * A thread that is awaiting the completion of a shut down procedure with
   * a timeout, must throw an InterruptedException if it is interrupted.
   * We test this the same way we test without the timeout. The only difference
   * is that our thread will enter the TIMED_WAITING state because of the
   * timeout.
   */
  @Test(timeout = TIMEOUT, expected = InterruptedException.class) public void
  awaitWithTimeoutOnCompletionMustThrowUponInterruption() throws Exception {
    createPool();
    GenericPoolable obj = pool.claim(longTimeout);
    assertNotNull("Did not deplete pool in time", obj);
    Completion completion = pool.shutdown();
    forkFuture($interruptUponState(
        Thread.currentThread(), Thread.State.TIMED_WAITING));
    try {
      completion.await(longTimeout);
    } finally {
      obj.release();
    }
  }
  
  /**
   * As per the contract of throwing an InterruptedException, if the
   * await of an unfinished completion throws an InterruptedException, then
   * they must also clear the interrupted status.
   */
  @Test(timeout = TIMEOUT) public void
  awaitOnCompletionWhenInterruptedMustClearInterruption() throws Exception {
    try {
      awaitOnCompletionWhenInterruptedMustThrow();
    } catch (InterruptedException ignore) {}
    assertFalse(Thread.interrupted());
    
    try {
      awaitWithTimeoutOnCompletionMustThrowUponInterruption();
    } catch (InterruptedException ignore) {}
    assertFalse(Thread.interrupted());
  }

  private Completion givenFinishedInterruptedCompletion()
      throws InterruptedException {
    createPool();
    pool.shutdown().await(longTimeout);
    Thread.currentThread().interrupt();
    return pool.shutdown();
  }
  
  /**
   * Calling await with a timeout on a finished completion when your thread
   * is interrupted must, just as with calling await without a timeout,
   * throw an InterruptedException.
   */
  @Test(timeout = TIMEOUT, expected = InterruptedException.class) public void
  awaitWithTimeoutOnFinishedCompletionWhenInterruptedMustThrow()
      throws InterruptedException {
    givenFinishedInterruptedCompletion().await(longTimeout);
  }
  
  /**
   * As per the contract of throwing an InterruptedException, the above must
   * also clear the threads interrupted status.
   */
  @Test(timeout = TIMEOUT) public void
  awaitOnFinishedCompletionMustClearInterruption() {
    try {
      awaitWithTimeoutOnFinishedCompletionWhenInterruptedMustThrow();
    } catch (InterruptedException ignore) {}
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
  @Test(timeout = TIMEOUT, expected = PoolException.class) public void
  claimMustThrowIfAllocationReturnsNull() throws Exception {
    Allocator<GenericPoolable> allocator = allocator(alloc($null));
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
  @Test(timeout = TIMEOUT, expected = PoolException.class) public void
  claimMustThrowIfReallocationReturnsNull() throws Exception {
    allocator = reallocator(realloc($null));
    AtomicBoolean hasExpired = new AtomicBoolean();
    config.setAllocator(allocator);
    config.setExpiration(expire($expiredIf(hasExpired)));
    config.setSize(2);
    createPool();
    // Make sure the pool is fully allocated
    hasExpired.set(false);
    GenericPoolable obj1 = pool.claim(longTimeout);
    GenericPoolable obj2 = pool.claim(longTimeout);
    obj1.release();
    obj2.release();
    // Now consider it expired. This will send it back for reallocation,
    // the reallocation turns it into null, which gets thrown as poison.
    // The pool will race to reallocate the poisoned objects, but it won't
    // win the race, because there are two slots circulating in the pool,
    // so it should be highly probable that we are able to grab one of them
    // while the other one is being reallocated.
    hasExpired.set(true);
    pool.claim(longTimeout);
  }
  
  /**
   * Threads that are already interrupted upon entry to the claim method, must
   * promptly be met with an InterruptedException. This behaviour matches that
   * of other interruptible methods in java.util.concurrent.
   * @see Pool
   */
  @Test(timeout = TIMEOUT, expected = InterruptedException.class) public void
  claimWhenInterruptedMustThrow() throws Exception {
    createPool();
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
  @Test(timeout = TIMEOUT, expected = InterruptedException.class) public void
  blockedClaimWithTimeoutMustThrowUponInterruption() throws Exception {
    createPool();
    GenericPoolable a = pool.claim(longTimeout);
    assertNotNull("Did not deplete pool in time", a);
    forkFuture($interruptUponState(
        Thread.currentThread(), Thread.State.TIMED_WAITING));
    try {
      pool.claim(longTimeout);
    } finally {
      a.release();
    }
  }

  /**
   * As per the general contract of interruptible methods, throwing an
   * InterruptedException will clear the interrupted flag on the thread.
   * This must also hold for the claim methods.
   */
  @Test(timeout = TIMEOUT) public void
  throwingInterruptedExceptionFromClaimMustClearInterruptedFlag()
      throws Exception {
    try {
      blockedClaimWithTimeoutMustThrowUponInterruption();
      fail("expected InterruptedException from claim");
    } catch (InterruptedException ignore) {}
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
  @Test(timeout = TIMEOUT) public void
  claimMustStayWithinDeadlineEvenIfAllocatorBlocks() throws Exception {
    Semaphore semaphore = new Semaphore(0);
    allocator = allocator(alloc($acquire(semaphore, $new)));
    config.setAllocator(allocator);
    createPool();
    try {
      pool.claim(shortTimeout);
    } finally {
      semaphore.release(10);
    }
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
  @Test(timeout = TIMEOUT) public void
  claimMustStayWithinTimeoutEvenIfExpiredObjectIsReleased() throws Exception {
    // NOTE: This test is a little slow and may hit the 42424 ms timeout even
    // if it was actually supposed to pass. Try running it again if there are
    // any problems. I may have to revisit this one in the future.
    final Poolable[] objs = new Poolable[50];
    final Lock lock = new ReentrantLock();
    allocator = allocator(alloc($sync(lock, $new)));
    config.setAllocator(allocator);
    config.setExpiration(fiveMsTTL);
    config.setSize(objs.length);
    createPool();
    for (int i = 0; i < objs.length; i++) {
      objs[i] = pool.claim(longTimeout);
      assertNotNull("Did not claim an object in time", objs[i]);
    }
    lock.lock(); // prevent new allocations
    Thread thread = fork($delayedReleases(objs, 10, TimeUnit.MILLISECONDS));
    try {
      // must return before test times out:
      GenericPoolable obj = pool.claim(new Timeout(50, TimeUnit.MILLISECONDS));
      if (obj != null) {
        obj.release();
      }
    } finally {
      thread.interrupt();
      //noinspection ThrowFromFinallyBlock
      thread.join();
      lock.unlock();
    }
  }
  
  /**
   * When claim is called with a timeout less than one, then it means that
   * no (observable amount of) waiting should take place.
   * <p>
   * We test for this by going through the numbers 0 to 99, both inclusive,
   * and call claim with those numbers as timeout values. The test is
   * considered to have passed, if this process completes within the 42424
   * millisecond timeout on the test case.
   * @see Pool#claim(Timeout)
   */
  @Test(timeout = TIMEOUT) public void
  claimWithTimeoutValueLessThanOneMustReturnImmediately() throws Exception {
    createPool();
    GenericPoolable obj = pool.claim(longTimeout);
    assertNotNull("Did not deplete pool in time", obj);
    try {
      pool.claim(zeroTimeout);
    } finally {
      obj.release();
    }
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
  @Test(timeout = TIMEOUT) public void
  awaitCompletionWithTimeoutLessThanOneMustReturnImmediately()
      throws Exception {
    createPool();
    GenericPoolable obj = pool.claim(longTimeout);
    try {
      assertNotNull("Did not deplete pool in time", obj);
      pool.shutdown().await(zeroTimeout);
    } finally {
      obj.release();
    }
  }
  
  /**
   * One must provide a Timeout argument when awaiting the completion of a
   * shut-down procedure. Passing null is thus an illegal argument.
   * @see Completion
   */
  @Test(timeout = TIMEOUT, expected = IllegalArgumentException.class) public void
  awaitCompletionWithNullTimeUnitMustThrow() throws Exception {
    createPool();
    pool.shutdown().await(null);
  }
  
  /**
   * Even if the Allocator has never made a successful allocation, the pool
   * must still be able to complete its shut-down procedure.
   * In this case we test with an allocator that always returns null.
   */
  @Test(timeout = TIMEOUT) public void
  mustCompleteShutDownEvenIfAllSlotsHaveNullErrors() throws Exception {
    Allocator<GenericPoolable> allocator = allocator(alloc($null));
    Pool<GenericPoolable> pool = givenPoolWithFailedAllocation(allocator);
    // the shut-down procedure must complete before the test times out.
    pool.shutdown().await(longTimeout);
  }

  private Pool<GenericPoolable> givenPoolWithFailedAllocation(
      Allocator<GenericPoolable> allocator) {
    config.setAllocator(allocator);
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
  @Test(timeout = TIMEOUT) public void
  mustCompleteShutDownEvenIfAllSlotsHaveAllocationErrors() throws Exception {
    Allocator<GenericPoolable> allocator =
        allocator(alloc($throw(new Exception("it's terrible stuff!!!"))));
    Pool<GenericPoolable> pool =
        givenPoolWithFailedAllocation(allocator);
    // must complete before the test timeout:
    pool.shutdown().await(longTimeout);
  }

  @Test(timeout = TIMEOUT) public void
  mustBeAbleToShutDownWhenAllocateAlwaysThrows() throws Exception {
    AtomicLong counter = new AtomicLong();
    allocator = allocator(alloc(
        $incrementAnd(counter, $throw(new RuntimeException("boo")))));
    config.setAllocator(allocator);
    config.setSize(3);
    createPool();
    //noinspection StatementWithEmptyBody
    while (counter.get() < 2)
    {
      // do nothing
    }
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
  @Test(timeout = TIMEOUT) public void
  mustBeAbleToShutDownEvenIfInterrupted() throws Exception {
    createPool();
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
  @Test(timeout = TIMEOUT) public void
  callingShutdownMustNotAffectInterruptionStatus() throws Exception {
    createPool();
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
  @Test(timeout = TIMEOUT) public void
  mustUnbiasObjectsNoLongerClaimed() throws Exception {
    createPool();
    Poolable obj = pool.claim(longTimeout);
    obj.release(); // item now biased to our thread
    // claiming in a different thread should give us the same object.
    AtomicReference<GenericPoolable> ref =
        new AtomicReference<>();
    join(forkFuture(capture($claim(pool, longTimeout), ref)));
    try {
      assertThat(ref.get(), is(obj));
    } finally {
      ref.get().release();
    }
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
  @Test(timeout = TIMEOUT) public void
  biasedClaimMustUpgradeToOrdinaryClaimIfTheObjectIsPulledFromTheQueue()
      throws Exception {
    createPool();
    pool.claim(longTimeout).release(); // bias the object to our thread
    GenericPoolable obj = pool.claim(longTimeout); // this is now our biased claim
    AtomicReference<GenericPoolable> ref = new AtomicReference<>();
    // the biased claim will be upgraded to an ordinary claim:
    join(forkFuture(capture($claim(pool, zeroTimeout), ref)));
    try {
      assertThat(ref.get(), nullValue());
    } finally {
      obj.release();
    }
  }
  
  /**
   * If a pool has been depleted, and then shut down, and another call to claim
   * comes in, then it must immediately throw an IllegalStateException.
   * Importantly, it must not block the thread to wait for any objects to be
   * released.
   */
  @Test(timeout = TIMEOUT, expected = IllegalStateException.class) public void
  depletedPoolThatHasBeenShutDownMustThrowUponClaim() throws Exception {
    createPool();
    GenericPoolable obj = pool.claim(longTimeout); // depleted
    pool.shutdown();
    try {
      pool.claim(longTimeout);
    } finally {
      obj.release();
    }
  }

  /**
   * Basically the same test as above, except now we wait for the shutdown
   * process to make a bit of progress. This might expose different race bugs.
   */
  @Test(timeout = TIMEOUT, expected = IllegalStateException.class) public void
  depletedPoolThatHasBeenShutDownMustThrowUponClaimEvenAfterSomeTime()
      throws Exception {
    createPool();
    GenericPoolable obj = pool.claim(longTimeout); // depleted
    pool.shutdown();
    spinwait(10);
    try {
      pool.claim(longTimeout);
    } finally {
      obj.release();
    }
  }
  
  /**
   * We must ensure that, for pool implementation that do biasing, the checking
   * of whether the pool has been shut down must come before even a biased
   * claim. Even though a biased claim might not do any waiting that a normal
   * claim might do, it is still important that the shutdown notification takes
   * precedence, because we don't know for how long the claimed object will be
   * held, or if there will be other biased claims in the future.
   */
  @Test(timeout = TIMEOUT, expected = IllegalStateException.class) public void
  poolThatHasBeenShutDownMustThrowUponClaimEvenIfItHasAvailableUnbiasedObjects()
      throws Exception {
    config.setSize(4);
    createPool();
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
  @Test(timeout = TIMEOUT) public void
  mustNotThrowWhenReleasingObjectClaimedByAnotherThread() throws Exception {
    createPool();
    GenericPoolable obj = forkFuture($claim(pool, longTimeout)).get();
    obj.release();
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
  @Test(timeout = TIMEOUT) public void
  mustNotCachePoisonedSlots() throws Exception {
    // First we prime the possible thread-local cache to a particular object.
    // Then we instruct the allocator to always throw an exception when it is
    // told to allocate on that particular slot.
    // Then, in another thread, we mark all objects in the pool as expired.
    // Once we have observed a reallocation attempt at our primed slot, we
    // try to reclaim it. The reclaim must not throw an exception because of
    // the cached poisoned slot.
    config.setSize(3);

    // Enough permits for each initial allocation:
    final Semaphore semaphore = new Semaphore(3);

    final AtomicBoolean hasExpired = new AtomicBoolean(false);
    config.setExpiration(expire($expiredIf(hasExpired)));

    final String allocationCause = "allocation blew up!";
    final AtomicReference<Slot> failOnAllocatingSlot =
        new AtomicReference<>();
    final AtomicInteger observedFailedAllocation = new AtomicInteger();
    Action observeFailure = (slot, obj) -> {
      if (slot == failOnAllocatingSlot.get()) {
        observedFailedAllocation.incrementAndGet();
        throw new RuntimeException(allocationCause);
      }
      return new GenericPoolable(slot);
    };
    allocator = allocator(alloc($acquire(semaphore, observeFailure)));
    config.setAllocator(allocator);

    createPool();

    // Wait for the pool to fill
    while (allocator.countAllocations() < 3) {
      Thread.yield();
    }

    // Prime any thread-local cache
    GenericPoolable obj = pool.claim(longTimeout);
    failOnAllocatingSlot.set(obj.slot);
    obj.release(); // Places slot at end of queue

    // Expire all poolables
    hasExpired.set(true);
    AtomicReference<GenericPoolable> ref =
        new AtomicReference<>();
    try {
      forkFuture(capture($claim(pool, shortTimeout), ref)).get();
    } catch (ExecutionException ignore) {
      // This is okay. We just got a failed reallocation
    }
    assertNull(ref.get());

    // Give the allocator enough permits to reallocate the whole pool, again
    semaphore.release(3);

    // Wait for our primed slot to get reallocated
    while(observedFailedAllocation.get() < 1) {
      Thread.yield();
    }
    spinwait(15); // Heuristically wait for the slot to become live

    // Things no longer expire...
    hasExpired.set(false);

    // ... so we should be able to claim without trouble
    pool.claim(longTimeout).release();
  }

  @Test(expected = IllegalArgumentException.class) public void
  targetSizeMustBeGreaterThanZero() {
    createPool();
    pool.setTargetSize(0);
  }

  @Test public void
  targetSizeMustBeConfiguredSizeByDefault() {
    config.setSize(23);
    createPool();
    assertThat(pool.getTargetSize(), is(23));
  }

  @Test public void
  getTargetSizeMustReturnLastSetTargetSize() {
    createPool();
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
  @Test(timeout = TIMEOUT) public void
  increasingSizeMustAllowMoreAllocations() throws Exception {
    createPool();
    GenericPoolable a = pool.claim(longTimeout); // depleted
    pool.setTargetSize(2);
    // now this mustn't block:
    GenericPoolable b = pool.claim(longTimeout);
    a.release();
    b.release();
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
  @Test(timeout = TIMEOUT) public void
  decreasingSizeMustEventuallyDeallocateSurplusObjects() throws Exception {
    int startingSize = 5;
    int newSize = 1;
    allocator = allocator();
    config.setSize(startingSize);
    config.setAllocator(allocator);
    createPool();
    List<GenericPoolable> objs = new ArrayList<>();

    while (allocator.countAllocations() != startingSize) {
      objs.add(pool.claim(longTimeout)); // force the pool to do work
    }
    pool.setTargetSize(newSize);
    while (allocator.countDeallocations() != startingSize - newSize) {
      if (objs.size() > 0) {
        objs.remove(0).release(); // give the pool objects to deallocate
      } else {
        pool.claim(longTimeout).release(); // prod it & poke it
      }
      LockSupport.parkNanos(10000000); // 10 millis
    }
    int actualSize =
        allocator.countAllocations() - allocator.countDeallocations();
    try {
      assertThat(actualSize, is(newSize));
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
  @Test(timeout = TIMEOUT) public void
  mustNotReallocateWhenReleasingExpiredObjectsIntoShrunkPool()
      throws Exception {
    int startingSize = 5;
    int newSize = 1;
    Expiration<GenericPoolable> expiration = expire(
        // our 5 items are not expired when we deplete the pool
        $fresh, $fresh, $fresh, $fresh, $fresh,
        // but all items we try to claim after that *are* expired.
        $expired
    );
    config.setExpiration(expiration).setAllocator(allocator);
    config.setSize(startingSize);
    createPool();
    List<GenericPoolable> objs = new ArrayList<>();
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
    try {
      assertThat(pool.claim(shortTimeout), nullValue());
      assertThat(allocator.countAllocations(), is(startingSize));
    } finally {
      objs.remove(0).release();
    }
  }

  @Test public void
  settingTargetSizeOnPoolThatHasBeenShutDownDoesNothing() {
    config.setSize(3);
    createPool();
    pool.shutdown();
    pool.setTargetSize(10); // this should do nothing, because it's shut down
    assertThat(pool.getTargetSize(), is(3));
  }

  /**
   * Make sure that the pool does not get into a bad state, caused by concurrent
   * background resizing jobs interfering with each other.
   *
   * We test this by creating a small pool, then resizing it larger (so much so that
   * any resizing job is unlikely to finish before we can make our next move) and then
   * immediately resizing it smaller again. This should put multiple resizing jobs in
   * flight. When all the background jobs complete, we should observe that the pool
   * ended up with exactly the target size number of items in it.
   */
  @Test(timeout = TIMEOUT) public void
  increasingAndDecreasingSizeInQuickSuccessionMustEventuallyReachTargetSize()
      throws Exception {
    createPool();

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
    GenericPoolable obj = pool.claim(longTimeout);
    try {
      assertThat(pool.claim(shortTimeout), nullValue());
    } finally {
      obj.release();
    }
  }

  /**
   * The specification only promises to correctly handle Expirations that throw
   * Exceptions, but we also test with Throwable, just in case we might be able
   * to recover from them as well.
   */
  @Test(timeout = TIMEOUT) public void
  mustNotLeakSlotsIfExpirationThrowsThrowableInsteadOfException()
      throws InterruptedException {
    final AtomicBoolean shouldThrow = new AtomicBoolean(true);
    config.setExpiration(expire(
        $if(shouldThrow,
            $throwExpire(new SomeRandomThrowable("Boom!")),
            $fresh)));

    createPool();
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

  @Test(timeout = TIMEOUT) public void
  mustProactivelyReallocatePoisonedSlotsWhenAllocatorStopsThrowingExceptions()
      throws Exception {
    final CountDownLatch allocationLatch = new CountDownLatch(1);
    allocator = allocator(alloc(
        $throw(new Exception("boom")),
        $countDown(allocationLatch, $new)));
    config.setAllocator(allocator);
    createPool();
    allocationLatch.await();
    GenericPoolable obj = pool.claim(longTimeout);
    try {
      assertThat(obj, is(notNullValue()));
    } finally {
      obj.release();
    }
  }

  @Test(timeout = TIMEOUT) public void
  mustProactivelyReallocatePoisonedSlotsWhenReallocatorStopsThrowingExceptions()
      throws Exception {
    final AtomicBoolean expired = new AtomicBoolean();
    final CountDownLatch allocationLatch = new CountDownLatch(2);
    allocator = reallocator(
        alloc($countDown(allocationLatch, $new)),
        realloc($throw(new Exception("boom")), $new));
    config.setAllocator(allocator);
    config.setExpiration(expire($expiredIf(expired)));
    createPool();
    expired.set(false); // first claimed object is not expired
    pool.claim(longTimeout).release(); // first object is fully allocated
    expired.set(true); // the next object we claim is expired
    GenericPoolable obj = pool.claim(zeroTimeout); // send back to reallocation
    assertThat(obj, is(nullValue()));
    allocationLatch.await();
    expired.set(false);
    obj = pool.claim(longTimeout);
    try {
      assertThat(obj, is(notNullValue()));
    } finally {
      obj.release();
    }
  }

  @Test(timeout = TIMEOUT) public void
  mustProactivelyReallocatePoisonedSlotsWhenAllocatorStopsReturningNull()
      throws Exception {
    final CountDownLatch allocationLatch = new CountDownLatch(1);
    allocator = allocator(
        alloc($null, $countDown(allocationLatch, $new)));
    config.setAllocator(allocator);
    createPool();
    allocationLatch.await();
    GenericPoolable obj = pool.claim(longTimeout);
    try {
      assertThat(obj, is(notNullValue()));
    } finally {
      obj.release();
    }
  }

  @Test(timeout = TIMEOUT) public void
  mustProactivelyReallocatePoisonedSlotsWhenReallocatorStopsReturningNull()
      throws Exception {
    AtomicBoolean expired = new AtomicBoolean();
    CountDownLatch allocationLatch = new CountDownLatch(1);
    Semaphore fixReallocLatch = new Semaphore(0);
    allocator = reallocator(
        alloc($new,
            $countDown(allocationLatch, $acquire(fixReallocLatch, $new))),
        realloc($null, $new));
    config.setAllocator(allocator);
    config.setExpiration(expire($expiredIf(expired)));
    createPool();
    expired.set(false); // first claimed object is not expired
    pool.claim(longTimeout).release(); // first object is fully allocated
    expired.set(true); // the next object we claim is expired
    GenericPoolable obj = pool.claim(zeroTimeout); // send back to reallocation
    assertThat(obj, is(nullValue()));
    fixReallocLatch.release();
    allocationLatch.await();
    expired.set(false);
    obj = pool.claim(longTimeout);
    try {
      assertThat(obj, is(notNullValue()));
    } finally {
      obj.release();
    }
  }

  @Test(timeout = TIMEOUT) public void
  mustNotFrivolouslyReallocateNonPoisonedSlotsDuringEagerRecovery()
      throws Exception {
    final CountDownLatch allocationLatch = new CountDownLatch(3);
    allocator = allocator(alloc(
        $countDown(allocationLatch, $null),
        $countDown(allocationLatch, $new)));
    config.setAllocator(allocator).setSize(2);
    createPool();
    allocationLatch.await();
    // The pool should now be fully allocated and healed
    GenericPoolable a = pool.claim(longTimeout);
    GenericPoolable b = pool.claim(longTimeout);
    try {
      assertThat(allocator.countAllocations(), is(3));
      assertThat(allocator.countDeallocations(), is(0)); // allocation failed
      assertThat(allocator.getDeallocations(), not(contains(a, b)));
    } finally {
      a.release();
      b.release();
    }
  }

  /**
   * If the interrupt happen inside the Allocator.allocate(Slot) method, and
   * then the allocator either clears the interruption status, or throws an
   * InterruptedException which in turn will be understood as poison, then the
   * allocation thread can miss the shutdown signal, and never begin the
   * shutdown sequence.
   */
  @Test(timeout = TIMEOUT) public void
  mustCompleteShutdownEvenIfAllocatorEatsTheInterruptSignal() throws Exception {
    config.setAllocator(reallocator(
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

  @Test(timeout = TIMEOUT) public void
  poolMustTolerateInterruptedExceptionFromAllocatorWhenNotShutDown()
      throws InterruptedException {
    config.setAllocator(
        allocator(alloc($throw(new InterruptedException("boom")), $new)));
    AtomicBoolean hasExpired = new AtomicBoolean();
    config.setExpiration(expire($expiredIf(hasExpired)));
    createPool();

    // This will capture the failed allocation:
    try {
      pool.claim(longTimeout).release();
    } catch (PoolException e) {
      assertThat(e.getCause(), instanceOf(InterruptedException.class));
    }

    // This should succeed like nothing happened:
    pool.claim(longTimeout).release();

    // Cause an extra reallocation to make sure that works:
    hasExpired.set(true);
    assertNull(pool.claim(shortTimeout));
    hasExpired.set(false);

    // These should again succeed like nothing happened:
    pool.claim(longTimeout).release();
    pool.claim(longTimeout).release();
    shutPoolDown();
  }

  @Test(timeout = TIMEOUT) public void
  poolMustUseConfiguredThreadFactoryWhenCreatingBackgroundThreads()
      throws InterruptedException {
    final ThreadFactory delegateThreadFactory = config.getThreadFactory();
    final List<Thread> createdThreads = new ArrayList<>();
    ThreadFactory factory = r -> {
      Thread thread = delegateThreadFactory.newThread(r);
      createdThreads.add(thread);
      return thread;
    };
    config.setThreadFactory(factory);
    createPool();
    pool.claim(longTimeout).release();
    assertThat(createdThreads.size(), is(1));
    assertTrue(createdThreads.get(0).isAlive());
    pool.shutdown().await(longTimeout);
    assertThat(createdThreads.size(), is(1));
    Thread thread = createdThreads.get(0);
    thread.join();
    assertFalse(thread.isAlive());
  }

  @Test public void
  managedPoolInterfaceMustBeMXBeanConformant() {
    assertTrue(JMX.isMXBeanInterface(ManagedPool.class));
  }

  @Test(timeout = TIMEOUT) public void
  managedPoolMustBeExposableThroughAnMBeanServerAsAnMXBean() throws Exception {
    config.setSize(3);
    ManagedPool managedPool = assumeManagedPool();
    GenericPoolable a = pool.claim(longTimeout);
    GenericPoolable b = pool.claim(longTimeout);
    GenericPoolable c = pool.claim(longTimeout);

    try {
      MBeanServer server = MBeanServerFactory.newMBeanServer("domain");
      ObjectName name = new ObjectName("domain:pool=stormpot");
      server.registerMBean(managedPool, name);
      ManagedPool proxy = JMX.newMBeanProxy(server, name, ManagedPool.class);

      assertThat(proxy.getAllocationCount(), is(3L));
    } finally {
      a.release();
      b.release();
      c.release();
    }
  }

  @Test(timeout = TIMEOUT) public void
  managedPoolMustCountAllocations() throws InterruptedException {
    AtomicBoolean hasExpired = new AtomicBoolean();
    config.setExpiration(expire($expiredIf(hasExpired)));
    CountingReallocator reallocator = reallocator();
    config.setAllocator(reallocator);
    ManagedPool managedPool = assumeManagedPool();

    pool.claim(longTimeout).release(); // expiration = false  ; allocations = 1
    pool.claim(longTimeout).release(); // expiration = false  ; allocations = 1

    hasExpired.set(true);
    assertNull(pool.claim(zeroTimeout));
    // expiration = true, false ; allocations = 2+
    // we're racing with how quickly the allocation thread can replenish the
    // pool, so we might get more than one allocation out of this
    hasExpired.set(false);

    pool.claim(longTimeout).release(); // expiration = false ; allocations = 2+

    long allocationCount = managedPool.getAllocationCount();
    long expectedCount =
        reallocator.countAllocations() + reallocator.countReallocations();
    assertThat(allocationCount, allOf(
        greaterThanOrEqualTo(2L), equalTo(expectedCount)));
  }

  @Test(timeout = TIMEOUT) public void
  managedPoolMustCountAllocationsFailingWithExceptions() throws Exception {
    CountDownLatch latch = new CountDownLatch(1);
    Exception exception = new Exception("boo");
    config.setSize(2).setAllocator(allocator(alloc(
        $new,
        $throw(exception),
        $throw(exception),
        $countDown(latch, $new))));
    ManagedPool managedPool = assumeManagedPool();

    // simply wait for the proactive healing to replace the failed allocations
    latch.await();

    assertThat(managedPool.getFailedAllocationCount(), is(2L));
  }

  @Test(timeout = TIMEOUT) public void
  managedPoolMustCountAllocationsFailingByReturningNull() throws Exception {
    config.setSize(2).setAllocator(
        allocator(alloc($new, $null, $null, $new)));
    ManagedPool managedPool = assumeManagedPool();
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

    assertThat(managedPool.getFailedAllocationCount(), is(2L));
  }

  @Test(timeout = TIMEOUT) public void
  managedPoolMustCountReallocationsFailingWithExceptions() throws Exception {
    config.setSize(1);
    Exception exception = new Exception("boo");
    config.setAllocator(reallocator(realloc($throw(exception), $new)));
    config.setExpiration(expire($expired, $fresh));
    ManagedPool managedPool = assumeManagedPool();

    GenericPoolable obj = null;
    do {
      try {
        obj = pool.claim(longTimeout);
      } catch (PoolException ignore) {}
    } while (obj == null);
    obj.release();

    assertThat(managedPool.getFailedAllocationCount(), is(1L));
  }

  @Test(timeout = TIMEOUT) public void
  managedPoolMustCountReallocationsFailingByReturningNull() throws Exception {
    config.setSize(1);
    config.setAllocator(reallocator(realloc($null, $new)));
    config.setExpiration(expire($expired, $fresh));
    ManagedPool managedPool = assumeManagedPool();

    GenericPoolable obj = null;
    do {
      try {
        obj = pool.claim(longTimeout);
      } catch (PoolException ignore) {}
    } while (obj == null);
    obj.release();

    assertThat(managedPool.getFailedAllocationCount(), is(1L));
  }

  @Test(timeout = TIMEOUT) public void
  managedPoolMustAllowGettingAndSettingPoolTargetSize() {
    config.setSize(2);
    ManagedPool managedPool = assumeManagedPool();
    assertThat(managedPool.getTargetSize(), is(2));
    managedPool.setTargetSize(5);
    assertThat(managedPool.getTargetSize(), is(5));
  }

  @Test(timeout = TIMEOUT) public void
  managedPoolMustGivePoolState() {
    ManagedPool managedPool = assumeManagedPool();

    assertFalse(managedPool.isShutDown());
  }

  @Test(timeout = TIMEOUT) public void
  managedPoolMustReturnNaNWhenNoMetricsRecorderHasBeenConfigured() {
    ManagedPool managedPool = assumeManagedPool();
    assertThat(managedPool.getAllocationLatencyPercentile(0.5),
        is(Double.NaN));
    assertThat(managedPool.getObjectLifetimePercentile(0.5),
        is(Double.NaN));
    assertThat(managedPool.getAllocationFailureLatencyPercentile(0.5),
        is(Double.NaN));
    assertThat(managedPool.getReallocationLatencyPercentile(0.5),
        is(Double.NaN));
    assertThat(managedPool.getReallocationFailureLatencyPercentile(0.5),
        is(Double.NaN));
    assertThat(managedPool.getDeallocationLatencyPercentile(0.5),
        is(Double.NaN));
  }

  @Test(timeout = TIMEOUT) public void
  managedPoolMustGetLatencyPercentilesFromConfiguredMetricsRecorder() {
    config.setMetricsRecorder(
        new FixedMeanMetricsRecorder(1.37, 2.37, 3.37, 4.37, 5.37, 6.37));
    ManagedPool managedPool = assumeManagedPool();
    assertThat(managedPool.getObjectLifetimePercentile(0.5),
        is(1.37));
    assertThat(managedPool.getAllocationLatencyPercentile(0.5),
        is(2.37));
    assertThat(managedPool.getAllocationFailureLatencyPercentile(0.5),
        is(3.37));
    assertThat(managedPool.getReallocationLatencyPercentile(0.5),
        is(4.37));
    assertThat(managedPool.getReallocationFailureLatencyPercentile(0.5),
        is(5.37));
    assertThat(managedPool.getDeallocationLatencyPercentile(0.5),
        is(6.37));
  }

  @Test(timeout = TIMEOUT) public void
  managedPoolMustRecordObjectLifetimeOnDeallocateInConfiguredMetricsRecorder()
      throws InterruptedException {
    CountDownLatch deallocLatch = new CountDownLatch(1);
    config.setMetricsRecorder(new LastSampleMetricsRecorder());
    config.setSize(2);
    config.setAllocator(reallocator(dealloc($countDown(deallocLatch, $null))));
    ManagedPool managedPool = assumeManagedPool();
    GenericPoolable a = pool.claim(longTimeout);
    GenericPoolable b = pool.claim(longTimeout);
    spinwait(5);
    a.release();
    b.release();
    pool.setTargetSize(1);
    deallocLatch.await();
    assertThat(managedPool.getObjectLifetimePercentile(0.0), allOf(
        greaterThanOrEqualTo(5.0),
        not(Double.NaN),
        lessThan(50000.0)));
  }

  @Test(timeout = TIMEOUT) public void
  managedPoolMustNotRecordObjectLifetimeLatencyBeforeFirstDeallocation()
      throws InterruptedException {
    config.setMetricsRecorder(new LastSampleMetricsRecorder());
    ManagedPool managedPool = assumeManagedPool();
    GenericPoolable obj = pool.claim(longTimeout);
    try {
      assertThat(managedPool.getObjectLifetimePercentile(0.0), is(Double.NaN));
    } finally {
      obj.release();
    }
  }

  @Test(timeout = TIMEOUT) public void
  managedPoolMustRecordObjectLifetimeOnReallocateInConfiguredMetricsRecorder()
      throws InterruptedException {
    config.setMetricsRecorder(new LastSampleMetricsRecorder());
    Semaphore semaphore = new Semaphore(0);
    config.setAllocator(reallocator(realloc($acquire(semaphore, $new), $new)));
    AtomicBoolean hasExpired = new AtomicBoolean();
    config.setExpiration(expire($expiredIf(hasExpired)));
    ManagedPool managedPool = assumeManagedPool();
    GenericPoolable obj = pool.claim(longTimeout);
    spinwait(5);
    obj.release();
    hasExpired.set(true);
    assertNull(pool.claim(zeroTimeout));
    hasExpired.set(false);
    semaphore.release(1);
    obj = pool.claim(longTimeout); // wait for reallocation
    try {
      assertThat(managedPool.getObjectLifetimePercentile(0.0), allOf(
          greaterThanOrEqualTo(5.0),
          not(Double.NaN),
          lessThan(50000.0)));
    } finally {
      obj.release();
    }
  }

  @Test(timeout = TIMEOUT)public void
  managedPoolLeakedObjectCountMustStartAtZero() {
    ManagedPool managedPool = assumeManagedPool();
    // Pools that don't support this feature return -1L.
    assertThat(managedPool.getLeakedObjectsCount(), is(0L));
  }

  @Test(timeout = TIMEOUT) public void
  managedPoolMustCountLeakedObjects() throws Exception {
    config.setSize(2);
    ManagedPool managedPool = assumeManagedPool();
    pool.claim(longTimeout); // leak!
    // Clear any thread-local reference to the leaked object:
    pool.claim(longTimeout).release();
    // Clear any references held by our particular allocator:
    allocator.clearLists();

    // A run of the GC will null out the weak-refs:
    System.gc();
    System.gc();
    System.gc();

    pool = null; // null out the pool because we can no longer shut it down.
    assertThat(managedPool.getLeakedObjectsCount(), is(1L));
  }

  @SuppressWarnings("UnusedAssignment")
  @Test(timeout = TIMEOUT)public void
  mustNotHoldOnToDeallocatedObjectsWhenLeakDetectionIsEnabled()
      throws Exception {
    // It's enabled by default
    AtomicBoolean hasExpired = new AtomicBoolean();
    config.setExpiration(expire($expiredIf(hasExpired)));
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

    // GC to force the object through finalization life cycle
    System.gc();
    System.gc();
    System.gc();

    // Now our weakReference must have been cleared
    assertNull(weakReference.get());
  }

  @Test(timeout = TIMEOUT) public void
  mustNotHoldOnToDeallocatedObjectsWhenLeakDetectionIsDisabled()
      throws Exception {
    // It's enabled by default, so just change that setting and run the same
    // test as above
    config.setPreciseLeakDetectionEnabled(false);
    mustNotHoldOnToDeallocatedObjectsWhenLeakDetectionIsEnabled();
  }

  @Test(timeout = TIMEOUT) public void
  managedPoolMustNotCountShutDownAsLeak() throws Exception {
    config.setSize(2);
    ManagedPool managedPool = assumeManagedPool();
    claimRelease(2, pool, longTimeout);
    pool.shutdown().await(longTimeout);
    allocator.clearLists();
    System.gc();
    System.gc();
    System.gc();
    assertThat(managedPool.getLeakedObjectsCount(), is(0L));
  }

  @Test(timeout = TIMEOUT) public void
  managedPoolMustNotCountResizeAsLeak() throws Exception {
    config.setSize(2);
    ManagedPool managedPool = assumeManagedPool();
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
    assertThat(managedPool.getLeakedObjectsCount(), is(0L));
  }

  @Test(timeout = TIMEOUT) public void
  managedPoolMustReturnMinusOneForLeakedObjectCountWhenDetectionIsDisabled() {
    config.setPreciseLeakDetectionEnabled(false);
    ManagedPool managedPool = assumeManagedPool();
    assertThat(managedPool.getLeakedObjectsCount(), is(-1L));
  }

  @Test(timeout = TIMEOUT) public void
  disabledLeakDetectionMustNotBreakResize() throws Exception {
    config.setPreciseLeakDetectionEnabled(false);
    config.setSize(2);
    ManagedPool managedPool = assumeManagedPool();
    claimRelease(2, pool, longTimeout);
    pool.setTargetSize(6);
    claimRelease(6, pool, longTimeout);
    pool.setTargetSize(2);
    while (allocator.countDeallocations() < 4) {
      spinwait(1);
    }
    assertThat(managedPool.getLeakedObjectsCount(), is(-1L));
  }

  @Test(timeout = TIMEOUT) public void
  mustCheckObjectExpirationInBackgroundWhenEnabled() throws Exception {
    CountDownLatch latch = new CountDownLatch(1);
    CountingReallocator reallocator = reallocator();
    CountingExpiration expiration = expire($expired, $countDown(latch, $fresh));
    config.setExpiration(expiration);
    config.setAllocator(reallocator);
    config.setBackgroundExpirationEnabled(true);
    createPool();

    latch.await();

    assertThat(reallocator.countAllocations(), is(1));
    assertThat(reallocator.countDeallocations(), is(0));
    assertThat(reallocator.countReallocations(), is(1));
  }

  @Test(timeout = TIMEOUT) public void
  objectMustBeClaimableAfterBackgroundReallocation() throws Exception {
    CountDownLatch latch = new CountDownLatch(1);
    CountingExpiration expiration =
        expire($countDown(latch, $expired), $fresh);
    config.setExpiration(expiration);
    config.setBackgroundExpirationEnabled(true);
    createPool();

    latch.await();

    pool.claim(longTimeout).release();
  }

  @Test(timeout = TIMEOUT) public void
  mustNotReallocateObjectsThatAreNotExpiredByTheBackgroundCheck()
      throws Exception {
    CountDownLatch latch = new CountDownLatch(2);
    CountingExpiration expiration = expire($countDown(latch, $fresh));
    CountingReallocator reallocator = reallocator();
    config.setExpiration(expiration);
    config.setBackgroundExpirationEnabled(true);
    createPool();

    latch.await();

    assertThat(reallocator.countReallocations(), is(0));
    assertThat(reallocator.countDeallocations(), is(0));
  }

  @Test(timeout = TIMEOUT) public void
  backgroundExpirationMustExpireObjectsWhenExpirationThrows() throws Exception {
    CountDownLatch latch = new CountDownLatch(1);
    CountingExpiration expiration = expire(
        $throwExpire(new Exception()),
        $countDown(latch, $fresh));
    config.setExpiration(expiration);
    config.setBackgroundExpirationEnabled(true);
    createPool();

    latch.await();

    assertThat(allocator.countAllocations(), is(2));
    assertThat(allocator.countDeallocations(), is(1));
  }

  @Test(timeout = TIMEOUT) public void
  backgroundExpirationMustNotExpireObjectsThatAreClaimed() throws Exception {
    AtomicBoolean hasExpired = new AtomicBoolean();
    CountDownLatch latch = new CountDownLatch(4);
    CountingExpiration expiration = expire(
        $countDown(latch, $expiredIf(hasExpired)));
    config.setExpiration(expiration);
    config.setBackgroundExpirationEnabled(true);
    config.setSize(2);
    createPool();

    // If applicable, do a thread-local reclaim
    pool.claim(longTimeout).release();
    GenericPoolable obj = pool.claim(longTimeout);
    hasExpired.set(true);

    latch.await();
    hasExpired.set(false);


    List<GenericPoolable> deallocations = allocator.getDeallocations();
    // Synchronized to guard against concurrent modification from the allocator
    synchronized (deallocations) {
      assertThat(deallocations, not(hasItem(obj)));
    }
    obj.release();
  }

  @Test(timeout = TIMEOUT) public void
  mustDeallocateExplicitlyExpiredObjects() throws Exception {
    int poolSize = 2;
    config.setSize(poolSize);
    createPool();

    // Explicitly expire a pool size worth of objects
    for (int i = 0; i < poolSize; i++) {
      GenericPoolable obj = pool.claim(longTimeout);
      obj.expire();
      obj.release();
    }

    // Grab and release a pool size worth objects as a barrier for reallocating
    // the expired objects
    List<GenericPoolable> objs = new ArrayList<>();
    for (int i = 0; i < poolSize; i++) {
      objs.add(pool.claim(longTimeout));
    }
    for (GenericPoolable obj : objs) {
      obj.release();
    }

    // Now we should see a pool size worth of reallocations
    assertThat("allocations", allocator.countAllocations(), is(2 * poolSize));
    assertThat("deallocations", allocator.countDeallocations(), is(poolSize));
  }

  @Test(timeout = TIMEOUT) public void
  mustReallocateExplicitlyExpiredObjectsInBackgroundWithBackgroundExpiration()
      throws Exception {
    CountDownLatch latch = new CountDownLatch(2);
    allocator = allocator(alloc($countDown(latch, $new)));
    config.setAllocator(allocator).setSize(1);
    config.setBackgroundExpirationEnabled(true);
    createPool();

    GenericPoolable obj = pool.claim(longTimeout);
    obj.expire();
    obj.release();

    latch.await();
  }

  @Test(timeout = TIMEOUT) public void
  mustReallocateExplicitlyExpiredObjectsInBackgroundWithoutBgExpiration()
      throws Exception {
    CountDownLatch latch = new CountDownLatch(2);
    allocator = allocator(alloc($countDown(latch, $new)));
    config.setAllocator(allocator).setSize(1);
    config.setBackgroundExpirationEnabled(false);
    createPool();

    GenericPoolable obj = pool.claim(longTimeout);
    obj.expire();
    obj.release();

    latch.await();
  }

  @Test(timeout = TIMEOUT) public void
  mustReplaceExplicitlyExpiredObjectsEvenIfDeallocationFails()
      throws Exception {
    allocator = allocator(dealloc($throw(new Exception("Boom!"))));
    config.setAllocator(allocator).setSize(1);
    createPool();

    GenericPoolable a = pool.claim(longTimeout);
    a.expire();
    a.release();

    GenericPoolable b = pool.claim(longTimeout);
    b.release();

    assertThat(a, not(sameInstance(b)));
  }

  @Test(timeout = TIMEOUT) public void
  explicitExpiryFromExpirationMustAllowOneClaimPerObject() throws Exception {
    config.setExpiration(expire($explicitExpire));
    createPool();

    GenericPoolable a = pool.claim(longTimeout);
    a.release();

    GenericPoolable b = pool.claim(longTimeout);
    b.release();

    assertThat(a, not(sameInstance(b)));
  }

  @Test(timeout = TIMEOUT) public void
  explicitlyExpiryMustBeIdempotent() throws Exception {
    createPool();

    GenericPoolable a = pool.claim(longTimeout);
    a.expire();
    a.expire();
    a.expire();
    a.release();

    GenericPoolable b = pool.claim(longTimeout);
    b.release();

    assertThat(a, not(sameInstance(b)));
    assertThat(allocator.countAllocations(), is(2));
    assertThat(allocator.countDeallocations(), is(1));
  }

  private static final Consumer<GenericPoolable> nullConsumer = (obj) -> {};

  @Test(timeout = TIMEOUT, expected = IllegalArgumentException.class) public void
  applyMustThrowOnNullTimeout() throws Exception {
    createPool();
    pool.apply(null, identity());
  }

  @Test(timeout = TIMEOUT, expected = IllegalArgumentException.class) public void
  supplyMustThrowOnNullTimeout() throws Exception {
    createPool();
    pool.supply(null, nullConsumer);
  }

  @Test(timeout = TIMEOUT, expected = NullPointerException.class) public void
  applyMustThrowOnNullFunction() throws Exception {
    createPool();
    pool.apply(longTimeout, null);
  }

  @Test(timeout = TIMEOUT, expected = NullPointerException.class) public void
  supplyMustThrowOnNullConsumer() throws Exception {
    createPool();
    pool.supply(longTimeout, null);
  }

  @Test(timeout = TIMEOUT) public void
  applyMustReturnEmptyIfTimeoutElapses() throws Exception {
    createPool();
    GenericPoolable obj = pool.claim(longTimeout);
    try {
      assertFalse(pool.apply(shortTimeout, identity()).isPresent());
    } finally {
      obj.release();
    }
  }

  @Test(timeout = TIMEOUT) public void
  supplyMustReturnFalseIfTimeoutElapses() throws Exception {
    createPool();
    GenericPoolable obj = pool.claim(longTimeout);
    try {
      assertFalse(pool.supply(shortTimeout, nullConsumer));
    } finally {
      obj.release();
    }
  }

  @Test(timeout = TIMEOUT) public void
  applyMustNotCallFunctionIfTimeoutElapses() throws Exception {
    createPool();
    GenericPoolable obj = pool.claim(longTimeout);
    try {
      AtomicInteger counter = new AtomicInteger();
      pool.apply(shortTimeout, (x) -> (Object) counter.incrementAndGet());
      assertThat(counter.get(), is(0));
    } finally {
      obj.release();
    }
  }

  @Test(timeout = TIMEOUT) public void
  supplyMustNotCallConsumerIfTimeoutElapses() throws Exception {
    createPool();
    GenericPoolable obj = pool.claim(longTimeout);
    try {
      AtomicReference<GenericPoolable> ref = new AtomicReference<>();
      pool.supply(shortTimeout, ref::set);
      assertThat(ref.get(), is(nullValue()));
    } finally {
      obj.release();
    }
  }

  @Test(timeout = TIMEOUT) public void
  applyMustCallFunctionIfObjectClaimedWithinTimeout() throws Exception {
    createPool();
    AtomicInteger counter = new AtomicInteger();
    pool.apply(longTimeout, (x) -> (Object) counter.incrementAndGet());
    assertThat(counter.get(), is(1));
  }

  @Test(timeout = TIMEOUT) public void
  supplyMustCallConsumerIfObjectClaimedWithinTimeout() throws Exception {
    createPool();
    GenericPoolable obj = pool.claim(longTimeout);
    obj.release();
    AtomicReference<GenericPoolable> ref = new AtomicReference<>();
    pool.supply(shortTimeout, ref::set);
    assertThat(ref.get(), is(sameInstance(obj)));
  }

  @Test(timeout = TIMEOUT) public void
  applyMustReturnResultOfFunction() throws Exception {
    createPool();
    String expectedResult = "Result!";
    Optional<String> actualResult =
        pool.apply(longTimeout, (obj) -> expectedResult);
    assertTrue(actualResult.isPresent());
    assertThat(actualResult.get(), is(expectedResult));
  }

  @Test(timeout = TIMEOUT) public void
  applyMustReturnEmptyIfFunctionReturnsNull() throws Exception {
    createPool();
    assertThat(pool.apply(longTimeout, (obj) -> null), is(Optional.empty()));
  }

  @Test(timeout = TIMEOUT) public void
  applyMustReleaseClaimedObject() throws Exception {
    createPool();
    pool.apply(longTimeout, identity());
    pool.apply(longTimeout, identity());
    fork(() -> pool.apply(longTimeout, identity())).join();
    fork(() -> pool.apply(longTimeout, identity())).join();
    pool.apply(longTimeout, identity());
    pool.apply(longTimeout, identity());
  }

  @Test(timeout = TIMEOUT) public void
  supplyMustReleaseClaimedObject() throws Exception {
    createPool();
    pool.supply(longTimeout, nullConsumer);
    pool.supply(longTimeout, nullConsumer);
    fork(() -> pool.supply(longTimeout, nullConsumer)).join();
    fork(() -> pool.supply(longTimeout, nullConsumer)).join();
    pool.supply(longTimeout, nullConsumer);
    pool.supply(longTimeout, nullConsumer);
  }

  private static Object expectException(Callable<?> callable) {
    try {
      callable.call();
      fail("The ExpectedException was not thrown");
    } catch (ExpectedException ignore) {
      // We expect this
    } catch (Exception e) {
      throw new AssertionError("Failed for other reason", e);
    }
    return null;
  }

  @Test(timeout = TIMEOUT) public void
  applyMustReleaseClaimedObjectEvenIfFunctionThrows() throws Exception {
    createPool();
    Function<GenericPoolable,Object> thrower = (obj) -> {
      throw new ExpectedException();
    };

    expectException(() -> pool.apply(longTimeout, thrower));
    expectException(() -> pool.apply(longTimeout, thrower));
    fork(() -> expectException(() -> pool.apply(longTimeout, thrower))).join();
    fork(() -> expectException(() -> pool.apply(longTimeout, thrower))).join();
    expectException(() -> pool.apply(longTimeout, thrower));
    expectException(() -> pool.apply(longTimeout, thrower));
  }

  @Test(timeout = TIMEOUT) public void
  supplyMustReleaseClaimedObjectEvenIfConsumerThrows() throws Exception {
    createPool();
    Consumer<GenericPoolable> thrower = (obj) -> {
      throw new ExpectedException();
    };

    expectException(() -> pool.supply(longTimeout, thrower));
    expectException(() -> pool.supply(longTimeout, thrower));
    fork(() -> expectException(() -> pool.supply(longTimeout, thrower))).join();
    fork(() -> expectException(() -> pool.supply(longTimeout, thrower))).join();
    expectException(() -> pool.supply(longTimeout, thrower));
    expectException(() -> pool.supply(longTimeout, thrower));
  }

  @Test(timeout = TIMEOUT, expected = IllegalStateException.class) public void
  applyMustThrowIfThePoolIsShutDown() throws Exception {
    createPool();
    pool.shutdown().await(longTimeout);
    pool.apply(longTimeout, identity());
  }

  @Test(timeout = TIMEOUT, expected = IllegalStateException.class) public void
  supplyMustThrowIfThePoolIsShutDown() throws Exception {
    createPool();
    pool.shutdown().await(longTimeout);
    pool.supply(longTimeout, nullConsumer);
  }

  @Test(timeout = TIMEOUT, expected = IllegalStateException.class) public void
  shuttingPoolDownMustUnblockApplyAndThrow() throws Throwable {
    createPool();
    GenericPoolable obj = pool.claim(longTimeout);
    Thread thread = fork(() -> pool.apply(longTimeout, identity()));
    AtomicReference<Throwable> exception = capture(thread);
    waitForThreadState(thread, Thread.State.TIMED_WAITING);
    Completion shutdown = pool.shutdown();
    join(thread);
    obj.release();
    shutdown.await(longTimeout);
    throw exception.get();
  }

  @Test(timeout = TIMEOUT, expected = IllegalStateException.class) public void
  shuttingPoolDownMustUnblockSupplyAndThrow() throws Throwable {
    createPool();
    GenericPoolable obj = pool.claim(longTimeout);
    Thread thread = fork(() -> pool.supply(longTimeout, nullConsumer));
    AtomicReference<Throwable> exception = capture(thread);
    waitForThreadState(thread, Thread.State.TIMED_WAITING);
    Completion shutdown = pool.shutdown();
    join(thread);
    obj.release();
    shutdown.await(longTimeout);
    throw exception.get();
  }

  @Test(timeout = TIMEOUT, expected = InterruptedException.class) public void
  applyMustThrowOnInterrupt() throws Exception {
    createPool();
    Thread.currentThread().interrupt();
    pool.apply(longTimeout, identity());
  }

  @Test(timeout = TIMEOUT, expected = InterruptedException.class) public void
  supplyMustThrowOnInterrupt() throws Exception {
    createPool();
    Thread.currentThread().interrupt();
    pool.supply(longTimeout, nullConsumer);
  }

  @Test(timeout = TIMEOUT, expected = InterruptedException.class) public void
  blockedApplyMustThrowOnInterrupt() throws Throwable {
    createPool();
    GenericPoolable obj = pool.claim(longTimeout);
    Thread thread = fork(() -> pool.apply(longTimeout, identity()));
    AtomicReference<Throwable> exception = capture(thread);
    waitForThreadState(thread, Thread.State.TIMED_WAITING);
    thread.interrupt();
    join(thread);
    obj.release();
    throw exception.get();
  }

  @Test(timeout = TIMEOUT, expected = InterruptedException.class) public void
  blockedSupplyMustThrowOnInterrupt() throws Throwable {
    createPool();
    GenericPoolable obj = pool.claim(longTimeout);
    Thread thread = fork(() -> pool.supply(longTimeout, nullConsumer));
    AtomicReference<Throwable> exception = capture(thread);
    waitForThreadState(thread, Thread.State.TIMED_WAITING);
    thread.interrupt();
    join(thread);
    obj.release();
    throw exception.get();
  }

  @Test(timeout = TIMEOUT) public void
  applyMustUnblockByConcurrentRelease() throws Exception {
    createPool();
    GenericPoolable obj = pool.claim(longTimeout);
    Thread thread = fork(() -> pool.apply(longTimeout, identity()));
    waitForThreadState(thread, Thread.State.TIMED_WAITING);
    obj.release();
    join(thread);
  }

  @Test(timeout = TIMEOUT) public void
  supplyMustUnblockByConcurrentRelease() throws Exception {
    createPool();
    GenericPoolable obj = pool.claim(longTimeout);
    Thread thread = fork(() -> pool.supply(longTimeout, nullConsumer));
    waitForThreadState(thread, Thread.State.TIMED_WAITING);
    obj.release();
    join(thread);
  }

  // NOTE: When adding, removing or modifying tests, also remember to update
  //       the javadocs and docs pages.
}
