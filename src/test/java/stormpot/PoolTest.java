package stormpot;

import static org.hamcrest.Matchers.*;
import static org.junit.Assert.*;
import static org.junit.Assume.*;
import static stormpot.UnitKit.*;

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
  
  @Test
  @Theory public void
  preventClaimFromPoolThatIsShutDown(PoolFixture fixture) {
    Pool pool = fixture.initPool();
    assumeThat(pool, instanceOf(LifecycledPool.class));
    ((LifecycledPool) pool).shutdown();
    try {
      pool.claim();
      fail("pool.claim() should have thrown");
    } catch (IllegalStateException _) {}
  }
  
  // TODO must replace expired Poolables
}
