package stormpot.basicpool;

import stormpot.Config;
import stormpot.Pool;
import stormpot.PoolFixture;

public class BasicPoolFixture implements PoolFixture {

  private Config config;

  public BasicPoolFixture(Config config) {
    this.config = config.copy();
  }

  public Pool initPool() {
    return initPool(config);
  }
  
  public Pool initPool(Config config) {
    BasicPool pool = new BasicPool(config);
    return pool;
  }
}
