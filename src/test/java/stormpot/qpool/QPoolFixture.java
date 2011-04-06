package stormpot.qpool;

import stormpot.Config;
import stormpot.Pool;
import stormpot.PoolFixture;

public class QPoolFixture implements PoolFixture {

  public Pool initPool(Config config) {
    return new QueuePool(config);
  }

}
