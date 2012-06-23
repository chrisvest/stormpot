package stormpot.benchmark;


/**
 * After warm-up, how many times can we claim and release non-expiring objects
 * in a given timeframe?
 * @author cvh
 */
public class SingleThreadedThroughputBenchmark extends Benchmark {
  @Override
  protected String getBenchmarkName() {
    return "Stormpot Single-Threaded Throughput Benchmark";
  }

  @Override
  protected void benchmark(Bench bench, long trialTimeMillis) throws Exception {
    long start = System.currentTimeMillis();
    long deadline = start + trialTimeMillis;
    long end = 0L;
    do {
      end = runCycles(bench, 100);
    } while (end < deadline);
    bench.recordPeriod(end - start);
  }

  @Override
  protected int[] warmupCycles() {
    return new int[] {1, 11, 1, 1};
  }
}
