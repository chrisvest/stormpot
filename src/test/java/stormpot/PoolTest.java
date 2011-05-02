package stormpot;

import static org.hamcrest.Matchers.*;
import static org.junit.Assert.*;
import static stormpot.UnitKit.*;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

import org.junit.Before;
import org.junit.Test;
import org.junit.experimental.theories.DataPoints;
import org.junit.experimental.theories.Theories;
import org.junit.experimental.theories.Theory;
import org.junit.runner.RunWith;

import stormpot.whirlpool.Whirlpool;

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
 * used to construct and initialise the pool, based on a Config.
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
@SuppressWarnings("unchecked")
@RunWith(Theories.class)
public class PoolTest {
  private static final long timeout = 1;
  private static final TimeUnit unit = TimeUnit.SECONDS;
  
  private CountingAllocator allocator;
  private Config config;
  
  @DataPoints
  public static PoolFixture[] pools() {
    return PoolFixtures.poolFixtures();
  }
  
  @Before public void
  setUp() {
    allocator = new CountingAllocator();
    config = new Config().setSize(1).setAllocator(allocator);
  }
  
  /**
   * The pool must not return null when we claim an object. Allocators also
   * are not allowed to ever return null (and the one used in this test lives
   * up to this promise) so if a null comes out then it means that the path
   * from the Allocator out of the pool is somehow broken.
   * @param fixture
   * @throws Exception
   */
  @Test(timeout = 300)
  @Theory public void
  claimMustReturnObject(PoolFixture fixture) throws Exception {
    Pool pool = fixture.initPool(config);
    Poolable obj = pool.claim();
    assertThat(obj, not(nullValue()));
  }
  
  /**
   * A call to claim-with-timeout must return before the timeout elapses if it
   * can claim an object from the pool, and it must return that object.
   * The timeout for the claim is longer than the timeout for the test, so we
   * know that we won't get a null back here because the timeout wasn't long
   * enough. If we do, then the pool does not correctly implement the timeout
   * behaviour.
   * @param fixture
   * @throws Exception
   */
  @Test(timeout = 300)
  @Theory public void
  claimWithTimeoutMustReturnIfWithinTimeout(PoolFixture fixture)
  throws Exception {
    Pool pool = fixture.initPool(config);
    Poolable obj = pool.claim(timeout, unit);
    assertThat(obj, not(nullValue()));
  }
  
  /**
   * A call to claim-with-timeout that fails to get an object before the
   * timeout elapses, must return null.
   * We test this by depleting a pool, and then make a call to claim with
   * a shot timeout. If that call returns <code>null</code>, then we're good.
   * @param fixture
   * @throws Exception
   */
  @Test(timeout = 300)
  @Theory public void
  claimWithTimeoutMustReturnNullIfTimeoutElapses(PoolFixture fixture)
  throws Exception {
    Pool pool = fixture.initPool(config);
    pool.claim(); // pool is now depleted
    Poolable obj = pool.claim(1, TimeUnit.MILLISECONDS);
    assertThat(obj, is(nullValue()));
  }
  
  /**
   * While the pool mustn't return null when we claim an object, it likewise
   * mustn't just come up with any random thing that implements Poolable.
   * The objects have to come from the associated Allocator.
   * Or fixtures are required to count all allocations and deallocations,
   * so we can measure that our intended interactions do, in fact, reach
   * the Allocator. The PoolFixture will typically do this by wrapping the
   * source Allocator in a CountingAllocatorWrapper, but that is an
   * irrelevant detail.
   * @param fixture
   * @throws Exception
   */
  @Test(timeout = 300)
  @Theory public void
  mustGetPooledObjectsFromAllocator(PoolFixture fixture) throws Exception {
    Pool pool = fixture.initPool(config);
    pool.claim();
    assertThat(allocator.allocations(), is(greaterThan(0)));
  }
  
  /**
   * If the pool has been depleted for objects, then it is generally the
   * contract of claim() to wait until one becomes available. There might
   * be options on pools to allow over-subscription or fail-fast strategies
   * to modify the behaviour of claim() but those are not considered in this
   * generic PoolTest.
   * So if a thread tries to claim from a depleted pool, then the thread must
   * be put in the WAITING state because it is waiting for some other thread
   * to perform a certain action, namely to release an object.
   * @param fixture
   * @throws Exception
   */
  @Test(timeout = 300)
  @Theory public void
  blockingClaimMustWaitIfPoolIsEmpty(PoolFixture fixture) throws Exception {
    Pool pool = fixture.initPool(config);
    pool.claim();
    Thread thread = fork($claim(pool));
    waitForThreadState(thread, Thread.State.WAITING);
  }
  
  /**
   * If the pool has been depleted for objects, then a call to claim with
   * timeout will wait until either an object becomes available, or the timeout
   * elapses. Whichever comes first.
   * We test for this by observing that a thread that makes a claim-with-timeout
   * call to a depleted pool, will enter the TIMED_WAITING state.
   * @param fixture
   * @throws Exception
   */
  @Test(timeout = 300)
  @Theory public void
  blockingClaimWithTimeoutMustWaitIfPoolIsEmpty(PoolFixture fixture)
  throws Exception {
    Pool pool = fixture.initPool(config);
    pool.claim();
    Thread thread = fork($claim(pool, timeout, unit));
    waitForThreadState(thread, Thread.State.TIMED_WAITING);
  }
  
  /**
   * When a thread is waiting in claim() on a depleted pool, then it is
   * basically waiting for another thread to release an object back into the
   * pool. Once this happens, the waiting thread must awaken to resume the
   * execution of claim() and get an object back out.
   * We only test the awakening here.
   * @param fixture
   * @throws Exception
   */
  @Test(timeout = 300)
  @Theory public void
  blockingOnClaimMustResumeWhenPoolablesAreReleased(PoolFixture fixture)
  throws Exception {
    Pool pool = fixture.initPool(config);
    Poolable obj = pool.claim();
    Thread thread = fork($claim(pool));
    waitForThreadState(thread, Thread.State.WAITING);
    obj.release();
    join(thread);
  }

  /**
   * A thread that is waiting in claim-with-timeout on a depleted pool must
   * wake up if another thread releases an object back into the pool.
   * So if we deplete a pool, make a thread wait in claim-with-timeout and
   * then release an object back into the pool, then we must be able to join
   * to that thread.
   * @param fixture
   * @throws Exception
   */
  @Test(timeout = 300)
  @Theory public void
  blockingOnClaimWithTimeoutMustResumeWhenPoolablesAreReleased(
      PoolFixture fixture) throws Exception {
    Pool pool = fixture.initPool(config);
    Poolable obj = pool.claim();
    Thread thread = fork($claim(pool, timeout, unit));
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
   * @param fixture
   * @throws Exception
   */
  @Test(timeout = 300)
  @Theory public void
  mustReuseAllocatedObjects(PoolFixture fixture)
  throws Exception {
    Pool pool = fixture.initPool(config);
    pool.claim().release();
    pool.claim().release();
    assertThat(allocator.allocations(), is(1));
  }
  
  /**
   * Be careful and prevent the creation of pools with a size less than one.
   * The contract of claim is to block indefinitely if one such pool were
   * to be created.
   * @param fixture
   */
  @Test(timeout = 300, expected = IllegalArgumentException.class)
  @Theory public void
  constructorMustThrowOnPoolSizeLessThanOne(PoolFixture fixture) {
    fixture.initPool(config.setSize(0));
  }
  
  /**
   * Prevent the creation of pools with a TTL value less than one.
   * @param fixture
   */
  @Test(timeout = 300, expected = IllegalArgumentException.class)
  @Theory public void
  constructorMustThrowOnTtlLessThanOne(PoolFixture fixture) {
    fixture.initPool(config.setTTL(0, unit));
  }
  
  /**
   * Prevent the creation of pools with a null TTL TimeUnit.
   * @param fixture
   */
  @Test(timeout = 300, expected = IllegalArgumentException.class)
  @Theory public void
  constructorMustThrowOnNullTtlUnit(PoolFixture fixture) {
    fixture.initPool(config.setTTL(timeout, null));
  }
  
  /**
   * Prevent the creation of pools with a null Allocator.
   * @param fixture
   */
  @Test(timeout = 300, expected = IllegalArgumentException.class)
  @Theory public void
  constructorMustThrowOnNullAllocator(PoolFixture fixture) {
    fixture.initPool(config.setAllocator(null));
  }
  
  /**
   * It is not possible to claim from a pool that has been shut down. Doing
   * so will cause an IllegalStateException to be thrown. This must take
   * effect as soon as shutdown has been called. So the fact that claim()
   * becomes unusable happens-before the pool shutdown process completes.
   * The memory effects of this are not tested for, but I don't think it is
   * possible to implement in a thread-safe manner and not provide the
   * memory effects that we want.
   * @param fixture
   * @throws Exception
   */
  @Test(timeout = 300, expected = IllegalStateException.class)
  @Theory public void
  preventClaimFromPoolThatIsShutDown(PoolFixture fixture) throws Exception {
    Pool pool = fixture.initPool(config);
    shutdown(pool);
    pool.claim();
  }

  /**
   * Trying to claim-with-timeout from a pool that has been shut down will
   * throw an IllegalStateException.
   * @see #preventClaimFromPoolThatIsShutDown(PoolFixture)
   * @param fixture
   * @throws Exception
   */
  @Test(timeout = 300, expected = IllegalStateException.class)
  @Theory public void
  preventClaimWithTimeoutFromPoolThatIsShutDown(PoolFixture fixture)
  throws Exception {
    Pool pool = fixture.initPool(config);
    shutdown(pool);
    pool.claim(timeout, unit);
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
   * two allocations, which is what we check for.
   * Pools that renew objects in a background thread, or otherwise
   * asynchronously, are going to have to deal with the negative TTL so we
   * don't get into any killer-busy-loops or odd-ball exceptions. 
   * @param fixture
   * @throws Exception
   */
  @Test(timeout = 300)
  @Theory public void
  mustReplaceExpiredPoolables(PoolFixture fixture) throws Exception {
    Pool pool = fixture.initPool(
        config.setTTL(1, TimeUnit.MILLISECONDS));
    pool.claim().release();
    spinwait(2);
    pool.claim().release();
    assertThat(allocator.allocations(), is(2));
    // TODO racy [1]!
  }
  
  /**
   * The size limit on a pool is strict, unless specially (as in a
   * non-standard way) configured otherwise. A pool is not allowed to 
   * have more objects allocated than the size, under any circumstances.
   * So, when the pool renews an object it must make ensure that the
   * deallocation of the old object happens-before the allocation of the
   * new object.
   * We test for this property by having a pool of size 1 and a negative TTL,
   * and then claiming and releasing an object two times in a row.
   * Because the TTL is negative, the object is expired when it is released
   * and must be deallocated before the next claim can allocate a new object.
   * @param fixture
   * @throws Exception
   */
  @Test(timeout = 300)
  @Theory public void
  mustDeallocateExpiredPoolablesAndStayWithinSizeLimit(PoolFixture fixture)
  throws Exception {
    Pool pool = fixture.initPool(
        config.setTTL(1, TimeUnit.MILLISECONDS));
    pool.claim().release();
    spinwait(2);
    pool.claim().release();
    assertThat(allocator.deallocations(), is(greaterThanOrEqualTo(1)));
    // We use greaterThanOrEqualTo because we cannot say whether the second
    // release() will deallocate as well - deallocation might be done
    // asynchronously. However, because the pool is not allowed to be larger
    // than 1, we can say for sure that the Poolable we claim first *must*
    // be deallocated before the allocation in the second claim.
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
   * @param fixture
   * @throws Exception
   */
  @Test(timeout = 300)
  @Theory public void
  mustDeallocateAllPoolablesBeforeShutdownTaskReturns(PoolFixture fixture)
  throws Exception {
    Pool pool = fixture.initPool(config.setSize(2));
    Poolable p1 = pool.claim();
    Poolable p2 = pool.claim();
    p1.release();
    p2.release();
    shutdown(pool).await();
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
   * @param fixture
   * @throws Exception
   */
  @Test(timeout = 300)
  @Theory public void
  shutdownCallMustReturnFastIfPoolablesAreStillClaimed(PoolFixture fixture)
  throws Exception {
    Pool pool = fixture.initPool(config);
    pool.claim();
    shutdown(pool);
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
   * @param fixture
   * @throws Exception
   */
  @Test(timeout = 300)
  @Theory public void
  shutdownMustNotDeallocateClaimedPoolables(PoolFixture fixture)
  throws Exception {
    Pool pool = fixture.initPool(config);
    pool.claim();
    shutdown(pool).await(10, TimeUnit.MILLISECONDS);
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
   * @param fixture
   * @throws Exception
   */
  @Test(timeout = 300)
  @Theory public void
  awaitOnShutdownMustReturnWhenClaimedObjectsAreReleased(PoolFixture fixture)
  throws Exception {
    Pool pool = fixture.initPool(config);
    Poolable obj = pool.claim();
    Completion completion = shutdown(pool);
    Thread thread = fork($await(completion));
    waitForThreadState(thread, Thread.State.WAITING);
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
   * @param fixture
   * @throws Exception
   */
  @Test(timeout = 300)
  @Theory public void
  awaitWithTimeoutMustReturnFalseIfTimeoutElapses(PoolFixture fixture)
  throws Exception {
    Pool pool = fixture.initPool(config);
    pool.claim();
    assertFalse(shutdown(pool).await(1, TimeUnit.MILLISECONDS));
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
   * @param fixture
   * @throws Exception
   */
  @Test(timeout = 300)
  @Theory public void
  awaitWithTimeoutMustReturnTrueIfCompletesWithinTimeout(PoolFixture fixture)
  throws Exception {
    Pool pool = fixture.initPool(config);
    Poolable obj = pool.claim();
    AtomicBoolean result = new AtomicBoolean(false);
    Completion completion = shutdown(pool);
    Thread thread =
      fork($await(completion, 500, TimeUnit.MILLISECONDS, result));
    waitForThreadState(thread, Thread.State.TIMED_WAITING);
    obj.release();
    join(thread);
    assertTrue(result.get());
  }
  
  /**
   * We have verified that the await methods works as intended, if you
   * begin your awaiting while the shut down process is still undergoing.
   * However, we must also make sure that further calls to await after the
   * shut down process has completed, do not block.
   * We do this by shutting a pool down, and then make a number of await calls
   * to the shut down Completion. These calls must all return before the
   * timeout of the test elapses.
   * @param fixture
   * @throws Exception
   */
  @Test(timeout = 300)
  @Theory public void
  awaitingOnAlreadyCompletedShutDownMustNotBlock(PoolFixture fixture)
  throws Exception {
    Completion completion = shutdown(fixture.initPool(config));
    completion.await();
    completion.await(1, TimeUnit.SECONDS);
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
   * @param fixture
   * @throws Exception
   */
  @Test(timeout = 300)
  @Theory public void
  blockedClaimMustThrowWhenPoolIsShutDown(PoolFixture fixture)
  throws Exception {
    Pool pool = fixture.initPool(config);
    AtomicReference caught = new AtomicReference();
    Poolable obj = pool.claim();
    Thread thread = fork($catchFrom($claim(pool), caught));
    waitForThreadState(thread, Thread.State.WAITING);
    shutdown(pool);
    obj.release();
    join(thread);
    assertThat(caught.get(), instanceOf(IllegalStateException.class));
  }

  /**
   * A thread that is waiting in claim-with-timeout when the pool is shut
   * down, must quickly unblock and receive an IllegalStateException.
   * @see #blockedClaimMustThrowWhenPoolIsShutDown(PoolFixture)
   * @param fixture
   * @throws Exception
   */
  @Test(timeout = 300)
  @Theory public void
  blockedClaimWithTimeoutMustThrowWhenPoolIsShutDown(PoolFixture fixture)
  throws Exception {
    Pool pool = fixture.initPool(config);
    AtomicReference caught = new AtomicReference();
    Poolable obj = pool.claim();
    Thread thread = fork($catchFrom($claim(pool, timeout, unit), caught));
    waitForThreadState(thread, Thread.State.TIMED_WAITING);
    shutdown(pool);
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
   * the TTL out and release it twice. Then claim an object to guarantee that
   * the deallocation of the first object have taken place when we check the
   * count. At this point, exactly one deallocation must have taken place.
   * No more, no less.
   * @param fixture
   * @throws Exception
   */
  @Test(timeout = 300)
  @Theory public void
  mustNotDeallocateTheSameObjectMoreThanOnce(PoolFixture fixture)
  throws Exception {
    Pool pool = fixture.initPool(
        config.setTTL(1, TimeUnit.MILLISECONDS));
    org.junit.Assume.assumeThat(pool, instanceOf(Whirlpool.class));
    Poolable obj = pool.claim();
    spinwait(2);
    obj.release();
    try {
      obj.release();
    } catch (Exception _) {
      // we don't really care if the pool is able to detect this or not
      // we are still going to check with the Allocator.
    }
    pool.claim();
    assertThat(allocator.deallocations(), is(1));
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
   * @param fixture
   * @throws Exception
   */
  @Test(timeout = 300)
  @Theory public void
  shutdownMustNotDeallocateEmptySlots(PoolFixture fixture) throws Exception {
    final AtomicBoolean wasNull = new AtomicBoolean();
    Allocator allocator = new CountingAllocator() {
      @Override
      public void deallocate(Poolable poolable) {
        if (poolable == null) {
          wasNull.set(true);
        }
      }
    };
    Pool pool = fixture.initPool(
        config.setAllocator(allocator).setTTL(1, TimeUnit.MILLISECONDS));
    pool.claim().release();
    shutdown(pool).await();
    assertFalse(wasNull.get());
  }
  
  /**
   * Pools must be prepared in the event that an Allocator throws a
   * RuntimeException. If it is not possible to allocate an object, then
   * the pool must throw a PoolException.
   * Preferably, this PoolException should wrap the original RuntimeException
   * from the Allocator, but we do not test for this here.
   * @param fixture
   * @throws Exception
   * @see PoolException
   */
  @Test(timeout = 300, expected = PoolException.class)
  @Theory public void
  mustPropagateExceptionsFromAllocateThroughClaim(PoolFixture fixture)
  throws Exception {
    Allocator allocator = new CountingAllocator() {
      @Override
      public Poolable allocate(Slot slot) {
        throw new RuntimeException("boo");
      }
    };
    Pool pool = fixture.initPool(config.setAllocator(allocator));
    pool.claim();
  }

  /**
   * If allocate throws an exception, then a later claim or claim-with-timeout
   * must propagate it wrapped in a PoolException.
   * We test the claim-with-timeout case here.
   * @see #mustPropagateExceptionsFromAllocateThroughClaim(PoolFixture)
   * @param fixture
   * @throws Exception
   */
  @Test(timeout = 300, expected = PoolException.class)
  @Theory public void
  mustPropagateExceptionsFromAllocateThroughClaimWithTimeout(
      PoolFixture fixture) throws Exception {
    Allocator allocator = new CountingAllocator() {
      @Override
      public Poolable allocate(Slot slot) {
        throw new RuntimeException("boo");
      }
    };
    Pool pool = fixture.initPool(config.setAllocator(allocator));
    pool.claim(timeout, unit);
  }
  
  /**
   * A pool must not break its internal invariants if an Allocator throws an
   * exception in allocate, and they must still be usable after the exception
   * has bubbled out.
   * We test this by configuring an Allocator that throws an exception if a
   * boolean variable is true, or allocates as normal if not. On the first
   * call to claim, we catch the exception that propagates out of the pool
   * and flip the boolean. Then the next call to claim must return a non-null
   * object within the test timeout.
   * If it does not, then the pool might have broken locks or it might have
   * garbage in the slot location.
   * @param fixture
   * @throws Exception
   */
  @Test(timeout = 300)
  @Theory public void
  mustStillBeUsableAfterExceptionInAllocate(PoolFixture fixture)
  throws Exception {
    final AtomicBoolean doThrow = new AtomicBoolean(true);
    Allocator allocator = new CountingAllocator() {
      @Override
      public Poolable allocate(Slot slot) throws Exception {
        if (doThrow.compareAndSet(true, false)) {
          throw new RuntimeException("boo");
        }
        return super.allocate(slot);
      }
    };
    Pool pool = fixture.initPool(config.setAllocator(allocator));
    try {
      pool.claim();
    } catch (PoolException _) {}
    assertThat(pool.claim(), is(notNullValue()));
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
   * @param fixture
   * @throws Exception
   */
  @Test(timeout = 300)
  @Theory public void
  mustSwallowExceptionsFromDeallocateThroughRelease(PoolFixture fixture)
  throws Exception {
    Allocator allocator = new CountingAllocator() {
      @Override
      public void deallocate(Poolable poolable) {
        throw new RuntimeException("boo");
      }
    };
    config.setAllocator(allocator);
    Pool pool = fixture.initPool(
        config.setTTL(1, TimeUnit.MILLISECONDS));
    pool.claim().release();
    spinwait(2);
    pool.claim();
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
   * @param fixture
   * @throws Exception
   */
  @Test(timeout = 300)
  @Theory public void
  mustSwallowExceptionsFromDeallocateThroughShutdown(PoolFixture fixture)
  throws Exception {
    CountingAllocator allocator = new CountingAllocator() {
      @Override
      public void deallocate(Poolable poolable) throws Exception {
        super.deallocate(poolable);
        throw new RuntimeException("boo");
      }
    };
    Pool pool = fixture.initPool(config.setAllocator(allocator).setSize(2));
    Poolable obj= pool.claim();
    pool.claim().release();
    obj.release();
    shutdown(pool).await();
    assertThat(allocator.deallocations(), is(2));
  }
  
  /**
   * Calling await on a completion when your thread is interrupted, must
   * throw an InterruptedException.
   * In this particular case we make sure that the shut down procedure has
   * not yet completed, by claiming an object from the pool without releasing
   * it.
   * @param fixture
   * @throws Exception
   */
  @Test(timeout = 300, expected = InterruptedException.class)
  @Theory public void
  awaitOnCompletionWhenInterruptedMustThrow(PoolFixture fixture)
  throws Exception {
    Completion completion = givenUnfineshedCompletion(fixture);
    Thread.currentThread().interrupt();
    completion.await();
  }

  private Completion givenUnfineshedCompletion(PoolFixture fixture)
  throws Exception {
    Pool pool = fixture.initPool(config);
    pool.claim();
    Completion completion = shutdown(pool);
    return completion;
  }
  
  /**
   * Calling await with timeout on a completion when your thread is
   * interrupted must, just as await without timeout, throw an
   * InterruptedException
   * @param fixture
   * @throws Exception
   */
  @Test(timeout = 300, expected = InterruptedException.class)
  @Theory public void
  awaitWithTimeoutOnCompletionWhenInterruptedMustThrow(PoolFixture fixture)
  throws Exception {
    Completion completion = givenUnfineshedCompletion(fixture);
    Thread.currentThread().interrupt();
    completion.await(1, TimeUnit.SECONDS);
  }
  
  /**
   * A thread that is waiting in await, for a completion to finish, must
   * throw an InterruptedException if it is interrupted.
   * We test this by starting another thread to interrupt us, as soon as it
   * observes that our thread enters the WAITING state.
   * @param fixture
   * @throws Exception
   */
  @Test(timeout = 300, expected = InterruptedException.class)
  @Theory public void
  awaitOnCompletionMustThrowUponInterruption(PoolFixture fixture)
  throws Exception {
    Completion completion = givenUnfineshedCompletion(fixture);
    fork($interruptUponState(Thread.currentThread(), Thread.State.WAITING));
    completion.await();
  }
  
  /**
   * A thread that is awaiting the completion of a shut down procedure with
   * a timeout, must throw an InterruptedException if it is interrupted.
   * We test this the same way we test without the timeout. The only difference
   * is that our thread will enter the TIMED_WAITING state because of the
   * timeout.
   * @param fixture
   * @throws Exception
   */
  @Test(timeout = 300, expected = InterruptedException.class)
  @Theory public void
  awaitWithTimeoutOnCompletionMustThrowUponInterruption(PoolFixture fixture)
  throws Exception {
    Completion completion = givenUnfineshedCompletion(fixture);
    fork($interruptUponState(
        Thread.currentThread(), Thread.State.TIMED_WAITING));
    completion.await(1, TimeUnit.SECONDS);
  }
  
  /**
   * As per the contract of throwing an InterruptedException, if the
   * await of an unfinished completion throws an InterruptedException, then
   * they must also clear the interrupted status.
   * @param fixture
   * @throws Exception
   */
  @Test(timeout = 300)
  @Theory public void
  awaitOnCompletionWhenInterruptedMustClearInterruption(PoolFixture fixture)
  throws Exception {
    try {
      awaitOnCompletionWhenInterruptedMustThrow(fixture);
    } catch (InterruptedException _) {}
    assertFalse(Thread.interrupted());
    
    try {
      awaitWithTimeoutOnCompletionWhenInterruptedMustThrow(fixture);
    } catch (InterruptedException _) {}
    assertFalse(Thread.interrupted());
    
    try {
      awaitOnCompletionMustThrowUponInterruption(fixture);
    } catch (InterruptedException _) {}
    assertFalse(Thread.interrupted());
    
    try {
      awaitWithTimeoutOnCompletionMustThrowUponInterruption(fixture);
    } catch (InterruptedException _) {}
    assertFalse(Thread.interrupted());
  }
  
  /**
   * Calling await on a completion when your thread is interrupted,
   * must throw an InterruptedException - even if the shut down procedure
   * has completed.
   * We test this by first shutting the pool down cleanly. Then interrupting
   * the thread, and then try to shut the pool down again.
   * @param fixture
   * @throws InterruptedException
   */
  @Test(timeout = 300, expected = InterruptedException.class)
  @Theory public void
  awaitOnFinishedCompletionWhenInterruptedMustThrow(PoolFixture fixture)
  throws InterruptedException {
    givenFinishedInterruptedCompletion(fixture).await();
  }

  private Completion givenFinishedInterruptedCompletion(PoolFixture fixture)
      throws InterruptedException {
    Pool pool = fixture.initPool(config);
    shutdown(pool).await();
    Thread.currentThread().interrupt();
    Completion completion = shutdown(pool);
    return completion;
  }
  
  /**
   * Calling await with a timeout on a finished completion when your thread
   * is interrupted must, just as with calling await without a timeout,
   * throw an InterruptedException.
   * @param fixture
   * @throws InterruptedException
   */
  @Test(timeout = 300, expected = InterruptedException.class)
  @Theory public void
  awaitWithTimeoutOnFinishedCompletionWhenInterruptedMustThrow(
      PoolFixture fixture) throws InterruptedException {
    givenFinishedInterruptedCompletion(fixture).await(1, TimeUnit.SECONDS);
  }
  
  /**
   * As per the contract of throwing an InterruptedException, the above must
   * also clear the threads interrupted status.
   * @param fixture
   */
  @Test(timeout = 300)
  @Theory public void
  awaitOnFinishedCompletionMustClearInterruption(PoolFixture fixture) {
    try {
      awaitOnFinishedCompletionWhenInterruptedMustThrow(fixture);
    } catch (InterruptedException _) {}
    assertFalse(Thread.interrupted());

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
   * @param fixture
   * @throws Exception
   * @see Allocator#allocate(Slot)
   * @see PoolException
   */
  @Test(timeout = 300, expected = PoolException.class)
  @Theory public void
  claimMustThrowIfAllocationReturnsNull(PoolFixture fixture) throws Exception {
    Allocator allocator = new CountingAllocator() {
      @Override
      public Poolable allocate(Slot slot) {
        return null;
      }
    };
    Pool pool = fixture.initPool(config.setAllocator(allocator));
    pool.claim();
  }
  
  /**
   * Even claim with timeout must throw a PoolException if an Allocator
   * returns <code>null</code>.
   * @see #claimMustThrowIfAllocationReturnsNull(PoolFixture)
   * @param fixture
   * @throws Exception
   */
  @Test(timeout = 300, expected = PoolException.class)
  @Theory public void
  claimWithTimeoutMustThrowIfAllocationReturnsNull(PoolFixture fixture)
  throws Exception {
    Allocator allocator = new CountingAllocator() {
      @Override
      public Poolable allocate(Slot slot) {
        return null;
      }
    };
    Pool pool = fixture.initPool(config.setAllocator(allocator));
    pool.claim(timeout, unit);
  }
  
  /**
   * Threads that are already interrupted upon entry to the claim method, must
   * promptly be met with an InterruptedException. This behaviour matches that
   * of other interruptible methods in java.util.concurrent.
   * @see Pool
   * @param fixture
   * @throws Exception
   */
  @Test(timeout = 300, expected = InterruptedException.class)
  @Theory public void
  claimWhenInterruptedMustThrow(PoolFixture fixture) throws Exception {
    Pool pool = fixture.initPool(config);
    Thread.currentThread().interrupt();
    pool.claim();
  }
  
  /**
   * @see #claimWhenInterruptedMustThrow(PoolFixture)
   * @see Pool
   * @param fixture
   * @throws Exception
   */
  @Test(timeout = 300, expected = InterruptedException.class)
  @Theory public void
  claimWithTimeoutWhenInterruptedMustThrow(PoolFixture fixture)
  throws Exception {
    Pool pool = fixture.initPool(config);
    Thread.currentThread().interrupt();
    pool.claim(timeout, unit);
  }
  
  /**
   * The claim methods checks whether the current thread is interrupted upon
   * entry, but perhaps what is more important is the interruption of a claim
   * call that is already waiting when the thread is interrupted.
   * We test for this by setting a thread to interrupt us, when our thread
   * enters the WAITING or TIMED_WAITING states. Then we make a call to the
   * appropriate claim method. If it throws an InterruptException, then the
   * test passes.
   * @param fixture
   * @throws Exception
   */
  @Test(timeout = 300, expected = InterruptedException.class)
  @Theory public void
  blockedClaimMustThrowUponInterruption(PoolFixture fixture) throws Exception {
    Pool pool = fixture.initPool(config);
    pool.claim();
    fork($interruptUponState(Thread.currentThread(), Thread.State.WAITING));
    pool.claim();
  }
  
  /**
   * @see #blockedClaimMustThrowUponInterruption(PoolFixture)
   * @param fixture
   * @throws Exception
   */
  @Test(timeout = 300, expected = InterruptedException.class)
  @Theory public void
  blockedClaimWithTimeoutMustThrowUponInterruption(PoolFixture fixture)
  throws Exception {
    Pool pool = fixture.initPool(config);
    pool.claim();
    fork($interruptUponState(
        Thread.currentThread(), Thread.State.TIMED_WAITING));
    pool.claim(timeout, unit);
  }

  /**
   * As per the general contract of interruptible methods, throwing an
   * InterruptedException will clear the interrupted flag on the thread.
   * This must also hold for the claim methods.
   * @param fixture
   * @throws Exception
   */
  @Test(timeout = 300)
  @Theory public void
  throwingInterruptedExceptionFromClaimMustClearInterruptedFlag(
      PoolFixture fixture) throws Exception {
    try {
      blockedClaimMustThrowUponInterruption(fixture);
      fail("expected InterruptedException from claim");
    } catch (InterruptedException _) {}
    assertFalse(Thread.interrupted());
    
    try {
      blockedClaimWithTimeoutMustThrowUponInterruption(fixture);
      fail("expected InterruptedException from claim-with-timeout");
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
   * @param fixture
   * @throws Exception
   */
  @Test(timeout = 300)
  @Theory public void
  claimMustStayWithinDeadlineEvenIfAllocatorBlocks(PoolFixture fixture)
  throws Exception {
    Allocator allocator = new CountingAllocator() {
      @Override
      public Poolable allocate(Slot slot) throws Exception {
        new CountDownLatch(1).await(); // this will never return
        return super.allocate(slot);
      }
    };
    Pool pool = fixture.initPool(config.setAllocator(allocator));
    pool.claim(10, TimeUnit.MILLISECONDS);
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
   * 
   * @param fixture
   * @throws Exception
   */
  @Test(timeout = 300)
  @Theory public void
  claimMustStayWithinTimeoutEvenIfExpiredObjectIsReleased(PoolFixture fixture)
  throws Exception {
    final Semaphore semaphore = new Semaphore(1);
    Allocator allocator = new CountingAllocator() {
      @Override
      public Poolable allocate(Slot slot) throws Exception {
        semaphore.acquire();
        return super.allocate(slot);
      }
    };
    Pool pool = fixture.initPool(
        config.setAllocator(allocator).setTTL(1, TimeUnit.MILLISECONDS));
    Poolable obj = pool.claim();
    fork($delayedRelease(obj, 40, TimeUnit.MILLISECONDS));
    long start = System.currentTimeMillis();
    pool.claim(50, TimeUnit.MILLISECONDS);
    long elapsed = System.currentTimeMillis() - start;
    assertThat(elapsed, lessThan(60L));
    // TODO racy!!!
  }
  // TODO test for resilience against spurious wake-ups?
  
  // NOTE: When adding, removing or modifying tests, also remember to update
  //       the Pool javadoc - especially the part about the promises.
}
