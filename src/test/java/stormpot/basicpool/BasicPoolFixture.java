package stormpot.basicpool;

import stormpot.Config;
import stormpot.Pool;
import stormpot.PoolFixture;

public class BasicPoolFixture implements PoolFixture {
  public Pool initPool(Config config) {
    BasicPool pool = new BasicPool(config);
    return pool;
  }
}