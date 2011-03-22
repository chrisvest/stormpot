package stormpot;

import static org.mockito.Mockito.*;

public class BasicPoolFixture implements PoolFixture {

  private Allocator allocator;
  private Config config;

  public BasicPoolFixture(Config config) {
    this.config = config.copy();
  }

  public Pool initPool() {
    allocator = mock(Allocator.class);
    Poolable poolable = new GenericPoolable(allocator);
    when(allocator.allocate()).thenReturn(poolable);
    BasicPool pool = new BasicPool(config, allocator);
    return pool;
  }

  public Allocator allocatorMock() {
    return allocator;
  }

}
