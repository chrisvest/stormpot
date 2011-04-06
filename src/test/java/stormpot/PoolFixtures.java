package stormpot;

import stormpot.basicpool.BasicPoolFixture;
import stormpot.qpool.QPoolFixture;

public class PoolFixtures {

  public static PoolFixture[] poolFixtures() {
    return new PoolFixture[] {
        new BasicPoolFixture(),
        new QPoolFixture(),
    };
  }
}
