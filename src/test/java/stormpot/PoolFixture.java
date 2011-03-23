package stormpot;

public interface PoolFixture {

  Pool initPool();

  Pool initPool(Config config);

  int allocations();
}
