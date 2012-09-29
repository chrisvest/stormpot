package stormpot.bpool;

import stormpot.Config;
import stormpot.Pool;
import stormpot.PoolFixture;
import stormpot.Poolable;

public class BPoolFixture implements PoolFixture {

  @Override
  public <T extends Poolable> Pool<T> initPool(Config<T> config) {
    return new BlazePool<T>(config);
  }
}
