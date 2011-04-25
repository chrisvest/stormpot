package stormpot;

import stormpot.basicpool.BasicPoolFixture;
import stormpot.qpool.QPoolFixture;
import stormpot.whirlpool.WhirlpoolFixture;

public class PoolFixtures {

  public static PoolFixture[] poolFixtures() {
    return new PoolFixture[] {
        new BasicPoolFixture(),
        new QPoolFixture(),
        new WhirlpoolFixture(),
    };
  }
}
