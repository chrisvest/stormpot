package stormpot.benchmark;

/**
 * After warm-up, how many times can we claim and release non-expiring objects
 * in a given timeframe?
 * @author cvh
 */
public class Throughput {
  private static final int SIZE = 10;
  private static final long TRIAL_TIME_MILLIS = 500L;

  public static void main(String[] args) throws Exception {
    Clock.start();
    System.out.println("Stormpot Single-Threaded Throughput Benchmark");
    try {
      runBenchmark();
    } finally {
      System.exit(0);
    }
  }

  private static void runBenchmark() throws Exception {
    Bench queuePool = new QueuePoolBench();
    queuePool.primeWithSize(SIZE);
    
    warmup(queuePool);
    trial(queuePool);
    trial(queuePool);
    trial(queuePool);
    trial(queuePool);
    trial(queuePool);
    trial(queuePool);
    trial(queuePool);
    trial(queuePool);
    trial(queuePool);
    trial(queuePool);
  }

  private static void warmup(Bench bench) throws Exception {
    System.out.println("Warming up " + bench.getName());
    int steps = 20;
    for (int i = 0; i < steps; i++) {
      for (int j = 0; j < 1000; j++) {
        benchmark(bench, 1);
      }
      System.out.printf("%02d/%s.", i, steps);
    }
    System.out.println("\nWarmup done.");
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
