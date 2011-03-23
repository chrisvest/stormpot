package stormpot;

import static org.hamcrest.Matchers.*;
import static org.junit.Assert.*;
import static stormpot.UnitKit.*;

import org.junit.Test;
import org.junit.experimental.theories.DataPoints;
import org.junit.experimental.theories.Theories;
import org.junit.experimental.theories.Theory;
import org.junit.runner.RunWith;

@RunWith(Theories.class)
public class PoolTest {
  private static final Config config = new Config();
  
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
    Pool pool = fixture.initPool(config.copy().setSize(1));
    pool.claim();
    Thread thread = fork($claim(pool));
    waitForThreadState(thread, Thread.State.WAITING);
  }
  
}
