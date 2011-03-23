package stormpot;

public class BasicPoolFixture implements PoolFixture {

  private CountingAllocatorWrapper allocator;
  private Config config;

  public BasicPoolFixture(Config config) {
    this.config = config.copy();
  }

  public Pool initPool() {
    return initPool(config);
  }
  
  public Pool initPool(Config config) {
    allocator = new CountingAllocatorWrapper(new GenericAllocator());
    BasicPool pool = new BasicPool(config, allocator);
    return pool;
  }

  public int allocations() {
    return allocator.countAllocations();
  }
}
