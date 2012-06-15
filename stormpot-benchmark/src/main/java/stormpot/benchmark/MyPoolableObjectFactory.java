package stormpot.benchmark;

import org.apache.commons.pool.PoolableObjectFactory;

public class MyPoolableObjectFactory implements PoolableObjectFactory<MyPoolable> {

  @Override
  public void activateObject(MyPoolable arg0) throws Exception {
  }

  @Override
  public void destroyObject(MyPoolable arg0) throws Exception {
  }

  @Override
  public MyPoolable makeObject() throws Exception {
    return new MyPoolable(null);
  }

  @Override
  public void passivateObject(MyPoolable arg0) throws Exception {
  }

  @Override
  public boolean validateObject(MyPoolable arg0) {
    return true;
  }
}
