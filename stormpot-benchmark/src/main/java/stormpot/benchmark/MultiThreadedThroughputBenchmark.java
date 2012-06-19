package stormpot.benchmark;

public class MultiThreadedThroughputBenchmark extends Benchmark {
  private static final int THREAD_COUNT = Integer.getInteger("thread.count",
        Runtime.getRuntime().availableProcessors());

  @Override
  protected String getBenchmarkName() {
    return "Stormpot Multi-Threaded Throughput Benchmark";
  }

  @Override
  protected void benchmark(Bench bench, long trialTimeMillis) throws Exception {
    // TODO Auto-generated method stub
    
  }

}
