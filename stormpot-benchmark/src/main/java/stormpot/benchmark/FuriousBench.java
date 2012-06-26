package stormpot.benchmark;

import nf.fr.eraasoft.pool.ObjectPool;
import nf.fr.eraasoft.pool.PoolException;
import nf.fr.eraasoft.pool.PoolSettings;
import nf.fr.eraasoft.pool.PoolableObjectBase;

public class FuriousBench extends Bench {
  private ObjectPool<MyPoolable> pool;

  @Override
  public void primeWithSize(int size, final long objTtlMillis) throws Exception {
    PoolableObjectBase<MyPoolable> allocator = new PoolableObjectBase<MyPoolable>() {
      @Override
      public MyPoolable make() throws PoolException {
        return new MyPoolable(null);
      }
      
      @Override
      public void activate(MyPoolable arg0) throws PoolException {
      }

      @Override
      public boolean validate(MyPoolable t) {
        return !t.olderThan(objTtlMillis);
      }
    };
    PoolSettings<MyPoolable> settings = new PoolSettings<MyPoolable>(allocator);
    settings.min(size).max(size);
    pool = settings.pool();
  }

  @Override
  public Object claim() throws Exception {
    return pool.getObj();
  }

  @Override
  public void release(Object object) throws Exception {
    pool.returnObj((MyPoolable) object);
  }
}
