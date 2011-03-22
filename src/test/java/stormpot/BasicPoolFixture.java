package stormpot;

public class BasicPoolFixture implements PoolFixture {

  private Config config;

  public BasicPoolFixture(Config config) {
    this.config = config.copy();
  }

  public Pool initPool() {
    return new BasicPool(config);
  }

}
