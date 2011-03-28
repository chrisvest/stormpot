package stormpot;

import stormpot.basicpool.BasicPoolFixture;

public class PoolFixtures {

  public static PoolFixture[] poolFixtures() {
    return new PoolFixture[] {
        new BasicPoolFixture(),
    };
  }
}
