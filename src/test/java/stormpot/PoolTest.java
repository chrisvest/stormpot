package stormpot;

import static org.hamcrest.Matchers.*;
import static org.junit.Assert.*;
import static stormpot.UnitKit.*;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

import org.junit.Before;
import org.junit.Test;
import org.junit.experimental.theories.DataPoints;
import org.junit.experimental.theories.Theories;
import org.junit.experimental.theories.Theory;
import org.junit.runner.RunWith;

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
 * @author Chris Vest <mr.chrisvest@gmail.com>
 *
 */
@RunWith(Theories.class)
public class PoolTest {
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
   * The pool mustn't return null when we claim an object. The Allocator
   * used in the tests never return null, so if a null comes out then it
   * means that the path from the Allocator out of the pool is somehow broken.
   * @param fixture
   */
  @Theory public void
  mustContainObjects(PoolFixture fixture) {
    Pool pool = fixture.initPool(config);
    Poolable obj = pool.claim();
    assertThat(obj, not(nullValue()));
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
   */
  @Theory public void
  mustGetPooledObjectsFromObjectSource(PoolFixture fixture) {
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
   */
  @Test(timeout = 300)
  @Theory public void
  blockingClaimMustWaitIfPoolIsEmpty(PoolFixture fixture) {
    Pool pool = fixture.initPool(config);
    pool.claim();
    Thread thread = fork($claim(pool));
    waitForThreadState(thread, Thread.State.WAITING);
  }
  
  /**
   * When a thread is waiting in claim() on a depleted pool, then it is
   * basically waiting for another thread to release an object back into the
   * pool. Once this happens, the waiting thread must awaken to resume the
   * execution of claim() and get an object back out.
   * We only test the awakening here.
   * @param fixture
   */
  @Test(timeout = 300)
  @Theory public void
  blockingOnClaimMustResumeWhenPoolablesAreReleased(PoolFixture fixture) {
    Pool pool = fixture.initPool(config);
    Poolable obj = pool.claim();
    Thread thread = fork($claim(pool));
    waitForThreadState(thread, Thread.State.WAITING);
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
   */
  @Theory public void
  mustReuseAllocatedObjects(PoolFixture fixture) {
    Pool pool = fixture.initPool(config);
    pool.claim().release();
    pool.claim().release();
    assertThat(allocator.allocations(), is(1));
  }
  
  /**
   * Be extra careful to prevent the creation of pools of size 0 or less,
   * even if the configuration is insane.
   * The contract of claim is to block indefinitely if one such pool were
   * to be created.
   * @param fixture
   */
  @Test(expected = IllegalArgumentException.class)
  @Theory public void
  preventConstructionOfPoolsOfSizeLessThanOne(PoolFixture fixture) {
    fixture.initPool(config.goInsane().setSize(0));
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
   */
  @Theory public void
  preventClaimFromPoolThatIsShutDown(PoolFixture fixture) {
    Pool pool = fixture.initPool(config);
    shutdown(pool);
    try {
      pool.claim();
      fail("pool.claim() should have thrown");
    } catch (IllegalStateException _) {}
  }

  /**
   * Objects in the pool only live for a certain amount of time, and then
   * they must be replaced/renewed. Pools should generally try to renew
   * before the timeout elapses for the given object, but we don't test for
   * that here.
   * We set the TTL to be -1 instead of 0 to avoid a data race on
   * {@link System#currentTimeMillis()}. This way, the objects will always
   * appear to have expired when checked. This means that every claim will
   * always allocate a new object, and so our two claims will translate to
   * two allocations, which is what we check for.
   * Pools that renew objects in a background thread, or otherwise
   * asynchronously, are going to have to deal with the negative TTL so we
   * don't get into any killer-busy-loops or odd-ball exceptions. 
   * @param fixture
   */
  @Theory public void
  mustReplaceExpiredPoolables(PoolFixture fixture) {
    Pool pool = fixture.initPool(
        config.goInsane().setTTL(-1L, TimeUnit.MILLISECONDS));
    pool.claim().release();
    pool.claim().release();
    assertThat(allocator.allocations(), is(2));
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
   */
  @Theory public void
  mustDeallocateExpiredPoolablesAndStayWithinSizeLimit(PoolFixture fixture) {
    Pool pool = fixture.initPool(
        config.goInsane().setTTL(-1L, TimeUnit.MILLISECONDS));
    pool.claim().release();
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
   */
  @Test(timeout = 300)
  @Theory public void
  shutdownCallMustReturnFastIfPoolablesAreStillClaimed(PoolFixture fixture) {
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
   */
  @Test(timeout = 300)
  @Theory public void
  awaitOnShutdownMustReturnWhenClaimedObjectsAreReleased(PoolFixture fixture) {
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
   */
  @Test(timeout = 300)
  @Theory public void
  awaitWithTimeoutMustReturnTrueIfCompletesWithinTimeout(PoolFixture fixture) {
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
   * Clients might hold on to objects after they have been released. This is
   * a user error, but pools must still maintain a coherent allocation and
   * deallocation pattern toward the Allocator.
   * We test this by configuring a pool with a negative TTL so that the objects
   * will be deallocated as soon as possible. Then we claim an object and
   * release it twice. Then claim an object to guarantee that the
   * deallocation of the first object have taken place when we check the count.
   * At this point, exactly one deallocation must have taken place. No more,
   * no less.
   * @param fixture
   */
  @Test(timeout = 300)
  @Theory public void
  mustNotDeallocateTheSameObjectMoreThanOnce(PoolFixture fixture) {
    Pool pool = fixture.initPool(
        config.goInsane().setTTL(-1, TimeUnit.MILLISECONDS));
    Poolable obj = pool.claim();
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
   * Allocator and a negative TTL, and claim and release an object. The
   * Allocator also counts down a latch, so that we don't have to race with
   * the deallocation. After the first object has been deallocated, we shut
   * the pool down. After the shut down procedure completes, we check that
   * no nulls were deallocated.
   * @param fixture
   * @throws Exception
   */
  @Test(timeout = 300)
  @Theory public void
  shutdownMustNotDeallocateEmptySlots(PoolFixture fixture) throws Exception {
    final AtomicBoolean wasNull = new AtomicBoolean();
    final CountDownLatch latch = new CountDownLatch(1);
    Allocator allocator = new CountingAllocator() {
      @Override
      public void deallocate(Poolable poolable) {
        if (poolable == null) {
          wasNull.set(true);
        }
        latch.countDown();
      }
    };
    Pool pool = fixture.initPool(config.goInsane()
        .setAllocator(allocator).setTTL(-1, TimeUnit.MILLISECONDS));
    pool.claim().release();
    latch.await();
    shutdown(pool).await();
    assertFalse(wasNull.get());
  }
  
  // TODO what if allocate throws?
  // TODO what if deallocate throws in release?
  // TODO what if deallocate throws in shutdown?
}
