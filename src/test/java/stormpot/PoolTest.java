package stormpot;

import static org.hamcrest.Matchers.*;
import static org.junit.Assert.*;
import static stormpot.UnitKit.*;

import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

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
  
  @Theory public void
  mustContainObjects(PoolFixture fixture) {
    Pool pool = fixture.initPool();
    Poolable obj = pool.claim();
    assertThat(obj, not(nullValue()));
  }
  
  @Theory public void
  mustGetPooledObjectsFromObjectSource(PoolFixture fixture) {
    Pool pool = fixture.initPool();
    pool.claim();
    assertThat(fixture.allocations(), is(greaterThan(0)));
  }
  
  @Test(timeout = 300)
  @Theory public void
  blockingClaimMustWaitIfPoolIsEmpty(PoolFixture fixture) {
    Pool pool = fixture.initPool();
    pool.claim();
    Thread thread = fork($claim(pool));
    waitForThreadState(thread, Thread.State.WAITING);
  }
  
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
  
  @Theory public void
  mustReuseAllocatedObjects(PoolFixture fixture) {
    Pool pool = fixture.initPool();
    pool.claim().release();
    pool.claim().release();
    assertThat(fixture.allocations(), is(1));
  }
  
  @Test(expected = IllegalArgumentException.class)
  @Theory public void
  preventConstructionOfPoolsOfSizeLessThanOne(PoolFixture fixture) {
    fixture.initPool(config.copy().goInsane().setSize(0));
  }
  
  @Theory public void
  preventClaimFromPoolThatIsShutDown(PoolFixture fixture) {
    Pool pool = fixture.initPool();
    shutdown(pool);
    try {
      pool.claim();
      fail("pool.claim() should have thrown");
    } catch (IllegalStateException _) {}
  }

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
}
