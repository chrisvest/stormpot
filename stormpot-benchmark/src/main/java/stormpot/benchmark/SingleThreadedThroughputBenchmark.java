package stormpot.benchmark;


/**
 * After warm-up, how many times can we claim and release non-expiring objects
 * in a given timeframe?
 * @author cvh
 */
public class SingleThreadedThroughputBenchmark extends Benchmark {
  private static final int SIZE = 10;
  private static final long TRIAL_TIME_MILLIS = 500L;
  private static final long OBJ_TTL_MILLIS = 5 * 60 * 100;

  public void run() {
    Clock.start();
    System.out.println(getBenchmarkName());
    try {
      runBenchmark();
    } catch (Exception e) {
      e.printStackTrace();
    }
  }

  protected String getBenchmarkName() {
    return "Stormpot Single-Threaded Throughput Benchmark";
  }

  private static void runBenchmark() throws Exception {
    Bench[] pools = buildPoolList();
    
    prime(pools, SIZE, OBJ_TTL_MILLIS);
    warmup(pools);
    trial(pools);
  }

  private static void warmup(Bench[] pools) throws Exception {
    System.out.println("Warming up pools...");
    for (Bench pool : pools) {
      warmup(pool, 1);
    }
    shuffle(pools);
    for (Bench pool : pools) {
      warmup(pool, 11);
    }
    shuffle(pools);
    for (Bench pool : pools) {
      warmup(pool, 1);
    }
    shuffle(pools);
    for (Bench pool : pools) {
      warmup(pool, 1);
    }
    System.out.println("Warmup done.");
  }

  private static void warmup(Bench bench, int steps) throws Exception {
    System.out.println(
        "Warming up " + bench.getName() + " with " + steps + "K steps.");
    for (int i = 0; i < steps; i++) {
      for (int j = 0; j < 1000; j++) {
        benchmark(bench, 1);
      }
      System.out.printf("%02d/%s.", i + 1, steps);
    }
    System.out.println("\ndone.");
  }

  private static void trial(Bench[] pools) throws Exception {
    for (int i = 0; i < 10; i++) {
      shuffle(pools);
      for (Bench pool : pools) {
        trial(pool);
      }
    }
  }

  private static void trial(Bench bench) throws Exception {
    Thread.sleep(10);
    benchmark(bench, TRIAL_TIME_MILLIS);
    bench.report();
  }

  private static void benchmark(Bench bench, long trialTimeMillis) throws Exception {
    bench.reset();
    
    long start = Clock.currentTimeMillis();
    long deadline = start + trialTimeMillis;
    long end = 0L;
    do {
      end = runCycles(bench, 100);
    } while (end < deadline);
    bench.recordPeriod(end - start);
  }

  private static long runCycles(Bench bench, int cycles) throws Exception {
    long start;
    long end = 0;
    for (int i = 0; i < cycles; i++) {
      start = Clock.currentTimeMillis();
      bench.claimAndRelease();
      end = Clock.currentTimeMillis();
      bench.recordTime(end - start);
    }
    return end;
  }
}
