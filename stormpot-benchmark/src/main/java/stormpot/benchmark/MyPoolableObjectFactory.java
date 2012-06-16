package stormpot.benchmark;

import org.apache.commons.pool.PoolableObjectFactory;

public class MyPoolableObjectFactory implements PoolableObjectFactory<MyPoolable> {
  private final long maxTtlMillis;
  
  public MyPoolableObjectFactory(long maxTtlMillis) {
    this.maxTtlMillis = maxTtlMillis;
  }

  @Override
  public void activateObject(MyPoolable obj) throws Exception {
  }

  @Override
  public void destroyObject(MyPoolable obj) throws Exception {
  }

  @Override
  public MyPoolable makeObject() throws Exception {
    return new MyPoolable(null);
  }

  @Override
  public void passivateObject(MyPoolable obj) throws Exception {
  }

  @Override
  public boolean validateObject(MyPoolable obj) {
    return !obj.olderThan(maxTtlMillis);
  }
}
