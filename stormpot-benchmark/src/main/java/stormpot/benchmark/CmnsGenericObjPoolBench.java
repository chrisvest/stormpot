package stormpot.benchmark;

import org.apache.commons.pool.ObjectPool;
import org.apache.commons.pool.PoolableObjectFactory;
import org.apache.commons.pool.impl.GenericObjectPool;

public class CmnsGenericObjPoolBench extends CmnsPoolBench {
  @Override
  protected ObjectPool<MyPoolable> buildPool(int size,
      PoolableObjectFactory<MyPoolable> factory) {
    return new GenericObjectPool<MyPoolable>(factory, size);
  }
}
