package stormpot;

import static org.hamcrest.Matchers.*;
import static org.junit.Assert.*;

import org.junit.experimental.theories.DataPoints;
import org.junit.experimental.theories.Theories;
import org.junit.experimental.theories.Theory;
import org.junit.runner.RunWith;

@RunWith(Theories.class)
public class PoolTest {
  @DataPoints
  public static PoolFixture[] pools() {
    Config config = new Config();
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
    try {
      assertThat(obj, not(nullValue()));
    } finally {
      obj.release();
    }
  }
}
