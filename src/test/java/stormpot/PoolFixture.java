package stormpot;

public interface PoolFixture {

  Pool initPool();

  int allocations();
}
