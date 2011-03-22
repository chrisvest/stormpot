package stormpot;

import static org.mockito.Mockito.*;

public class BasicPoolFixture implements PoolFixture {

  private ObjectSource objectSource;
  private Config config;

  public BasicPoolFixture(Config config) {
    this.config = config.copy();
  }

  public Pool initPool() {
    objectSource = mock(ObjectSource.class);
    Poolable poolable = new GenericPoolable(objectSource);
    when(objectSource.allocate()).thenReturn(poolable);
    BasicPool pool = new BasicPool(config, objectSource);
    return pool;
  }

  public ObjectSource objectSourceMock() {
    return objectSource;
  }

}
