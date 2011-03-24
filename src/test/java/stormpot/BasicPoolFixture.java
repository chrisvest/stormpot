package stormpot;

public class BasicPoolFixture implements PoolFixture {

  private CountingAllocatorWrapper allocator;
  private Config config;

  public BasicPoolFixture(Config config) {
    this.config = config.copy();
    this.allocator = new CountingAllocatorWrapper(new GenericAllocator());
  }

  public Pool initPool() {
    return initPool(config);
  }
  
  public Pool initPool(Config config) {
    BasicPool pool = new BasicPool(config, allocator);
    return pool;
  }

  public int allocations() {
    return allocator.countAllocations();
  }

  public int deallocations() {
    return allocator.countDeallocations();
  }
}
