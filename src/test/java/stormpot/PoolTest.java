package stormpot;

import static org.hamcrest.Matchers.*;
import static org.junit.Assert.*;
import static stormpot.UnitKit.*;

import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

import org.junit.Test;
import org.junit.experimental.theories.DataPoints;
import org.junit.experimental.theories.Theories;
import org.junit.experimental.theories.Theory;
import org.junit.runner.RunWith;

@RunWith(Theories.class)
public class PoolTest {
  private static final Config config = new Config().setSize(1);
  
  @DataPoints
  public static PoolFixture[] pools() {
    return new PoolFixture[] {
        basicPool(config),
    };
  }

  private static PoolFixture basicPool(Config config) {
    return new BasicPoolFixture(config);
  }
  
  /**
   * The pool mustn't return null when we claim an object. The Allocator
   * used in the tests never return null, so if a null comes out then it
   * means that the path from the Allocator out of the pool is somehow broken.
   * @param fixture
   */
  @Theory public void
  mustContainObjects(PoolFixture fixture) {
    Pool pool = fixture.initPool();
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
    Pool pool = fixture.initPool();
    pool.claim();
    assertThat(fixture.allocations(), is(greaterThan(0)));
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
    Pool pool = fixture.initPool();
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
    Pool pool = fixture.initPool();
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
    Pool pool = fixture.initPool();
    pool.claim().release();
    pool.claim().release();
    assertThat(fixture.allocations(), is(1));
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
    fixture.initPool(config.copy().goInsane().setSize(0));
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
    Pool pool = fixture.initPool();
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
        config.copy().goInsane().setTTL(-1L, TimeUnit.MILLISECONDS));
    pool.claim().release();
    pool.claim().release();
    assertThat(fixture.allocations(), is(2));
  }
  
  @Theory public void
  mustDeallocateExpiredPoolablesAndStayWithinSizeLimit(PoolFixture fixture) {
    Pool pool = fixture.initPool(
        config.copy().goInsane().setTTL(-1L, TimeUnit.MILLISECONDS));
    pool.claim().release();
    pool.claim().release();
    assertThat(fixture.deallocations(), is(greaterThanOrEqualTo(1)));
    // We use greaterThanOrEqualTo because we cannot say whether the second
    // release() will deallocate as well - deallocation might be done
    // asynchronously. However, because the pool is not allowed to be larger
    // than 1, we can say for sure that the Poolable we claim first *must*
    // be deallocated before the allocation in the second claim.
  }
  
  @Theory public void
  mustDeallocateAllPoolablesBeforeShutdownTaskReturns(PoolFixture fixture)
  throws Exception {
    Pool pool = fixture.initPool(config.copy().setSize(2));
    Poolable p1 = pool.claim();
    Poolable p2 = pool.claim();
    p1.release();
    p2.release();
    shutdown(pool).await();
    assertThat(fixture.deallocations(), is(2));
  }
  
  @Test(timeout = 300)
  @Theory public void
  shutdownCallMustReturnFastIfPoolablesAreStillClaimed(PoolFixture fixture) {
    Pool pool = fixture.initPool();
    pool.claim();
    shutdown(pool);
  }
  
  @Theory public void
  shutdownMustNotDeallocateClaimedPoolables(PoolFixture fixture) {
    Pool pool = fixture.initPool();
    pool.claim();
    shutdown(pool);
    assertThat(fixture.deallocations(), is(0));
  }
  
  @Test(timeout = 300)
  @Theory public void
  awaitOnShutdownMustReturnWhenClaimedObjectsAreReleased(PoolFixture fixture) {
    Pool pool = fixture.initPool();
    Poolable obj = pool.claim();
    Completion completion = shutdown(pool);
    Thread thread = fork($await(completion));
    waitForThreadState(thread, Thread.State.WAITING);
    obj.release();
    join(thread);
  }
  
  @Test(timeout = 300)
  @Theory public void
  awaitWithTimeoutMustReturnFalseIfTimeoutElapses(PoolFixture fixture)
  throws Exception {
    Pool pool = fixture.initPool();
    pool.claim();
    assertFalse(shutdown(pool).await(1, TimeUnit.MILLISECONDS));
  }
  
  @Test(timeout = 300)
  @Theory public void
  awaitWithTimeoutMustReturnTrueIfCompletesWithinTimeout(PoolFixture fixture) {
    Pool pool = fixture.initPool();
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
  
  @Test(timeout = 300)
  @Theory public void
  blockedClaimMustThrowWhenPoolIsShutDown(PoolFixture fixture)
  throws Exception {
    Pool pool = fixture.initPool();
    AtomicReference caught = new AtomicReference();
    Poolable obj = pool.claim();
    Thread thread = fork($catchFrom($claim(pool), caught));
    waitForThreadState(thread, Thread.State.WAITING);
    shutdown(pool);
    obj.release();
    join(thread);
    assertThat(caught.get(), instanceOf(IllegalStateException.class));
  }
}
