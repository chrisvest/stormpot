package stormpot.benchmark;

import org.apache.commons.pool.ObjectPool;
import org.apache.commons.pool.PoolableObjectFactory;

public abstract class CmnsPoolBench extends Bench {
  
  private ObjectPool<MyPoolable> pool;

  @Override
  public void primeWithSize(int size, long objTtlMillis) throws Exception {
    PoolableObjectFactory<MyPoolable> factory =
        new MyPoolableObjectFactory(objTtlMillis);
    pool = buildPool(size, factory);
  }
  
  protected abstract ObjectPool<MyPoolable> buildPool(
      int size, PoolableObjectFactory<MyPoolable> factory);

  @Override
  public Object claim() throws Exception {
    return pool.borrowObject();
  }

  @Override
  public void release(Object object) throws Exception {
    pool.returnObject((MyPoolable) object);
  }

  @Override
  public String getName() {
    return pool.getClass().getSimpleName();
  }
}
