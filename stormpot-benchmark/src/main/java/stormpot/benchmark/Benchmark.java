package stormpot.benchmark;

import java.util.Random;

public abstract class Benchmark {
  private static final Random rnd = new Random();

  protected static Bench[] buildPoolList() {
    return new Bench[] {
        new QueuePoolBench(),
        new QueuePoolWithClockTtlBench(),
        new CmnsStackPoolBench(),
        new CmnsGenericObjPoolBench()};
  }

  protected static void prime(Bench[] pools, int size, long objTtlMillis)
      throws Exception {
    for (Bench pool : pools) {
      pool.primeWithSize(size, objTtlMillis);
    }
  }

  protected static void shuffle(Bench[] pools) {
    for (int i = 0; i < pools.length; i++) {
      int index = i + rnd.nextInt(pools.length - i);
      Bench tmp = pools[index];
      pools[index] = pools[i];
      pools[i] = tmp;
    }
  }

  public abstract void run();
}
