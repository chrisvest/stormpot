package stormpot.whirlpool;

import stormpot.Config;
import stormpot.Pool;
import stormpot.PoolFixture;

public class WhirlpoolFixture implements PoolFixture {

  @SuppressWarnings("unchecked")
  public Pool initPool(Config config) {
    return new Whirlpool(config);
  }

}
