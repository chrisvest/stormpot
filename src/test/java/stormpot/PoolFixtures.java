package stormpot;

import stormpot.basicpool.BasicPoolFixture;

public class PoolFixtures {

  public static PoolFixture[] poolFixtures(Config config) {
    return new PoolFixture[] {
        new BasicPoolFixture(config),
    };
  }

}
