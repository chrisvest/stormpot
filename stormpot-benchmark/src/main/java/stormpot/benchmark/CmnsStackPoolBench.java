package stormpot.benchmark;

import org.apache.commons.pool.PoolableObjectFactory;
import org.apache.commons.pool.impl.StackObjectPool;

public class CmnsStackPoolBench extends CmnsPoolBench {
  protected StackObjectPool<MyPoolable> buildPool(
      int size, PoolableObjectFactory<MyPoolable> factory) {
    return new StackObjectPool<MyPoolable>(factory, size);
  }
}
