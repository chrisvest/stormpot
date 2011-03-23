package stormpot;

public interface PoolFixture {

  Pool initPool();

  Pool initPool(Config setSize);

  int allocations();
}
