package stormpot.benchmark;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicInteger;

public class MultiThreadedThroughputBenchmark extends Benchmark {
  private static final int THREAD_COUNT = Integer.getInteger("thread.count",
        Runtime.getRuntime().availableProcessors());

  private WorkerThread[] threads;
  private volatile CountDownLatch startLatch;
  private volatile CountDownLatch doneLatch;
  
  @Override
  protected String getBenchmarkName() {
    return "Stormpot Multi-Threaded Throughput Benchmark";
  }

  @Override
  protected int[] warmupCycles() {
    return new int[] {1, 13, 1, 1, 13, 1, 1};
  }

  @Override
  protected void warmup(Bench bench, int steps) throws Exception {
    System.out.println(
        "Warming up " + bench.getName() + " with " + steps + " steps.");
    for (int i = 0; i < steps; i++) {
      prepareAndRunBenchmark(bench, 1000);
      System.out.printf("%02d/%02d.", i + 1, steps);
    }
    System.out.println("\ndone.");
  }

  @Override
  protected void beforeBenchmark(Bench bench, long trialTimeMillis) {
    super.beforeBenchmark(bench, trialTimeMillis);
    
    startLatch = new CountDownLatch(1);
    doneLatch = new CountDownLatch(THREAD_COUNT);
    threads = new WorkerThread[THREAD_COUNT];
    for (int i = 0; i < THREAD_COUNT; i++) {
      threads[i] = new WorkerThread(bench, startLatch, doneLatch);
      threads[i].start();
    }
  }

  @Override
  protected void benchmark(Bench bench, long trialTimeMillis) throws Exception {
    long start = System.currentTimeMillis();
    long deadline = start + trialTimeMillis;
    for (WorkerThread th : threads) {
      th.deadline = deadline;
    }
    startLatch.countDown();
    doneLatch.await();
    long end = System.currentTimeMillis();
    bench.recordPeriod(end - start);
  }
  
  private static class WorkerThread extends Thread {
    private static final AtomicInteger counter = new AtomicInteger();
    public volatile long deadline;
    private final Bench bench;
    private final CountDownLatch doneLatch;
    private final CountDownLatch startLatch;
    
    public WorkerThread(Bench bench, CountDownLatch startLatch, CountDownLatch doneLatch) {
      super("WorkerThread-" + counter.incrementAndGet());
      setDaemon(true);
      this.bench = bench;
      this.startLatch = startLatch;
      this.doneLatch = doneLatch;
    }
    
    @Override
    public void run() {
      try {
        startLatch.await();
        doBenchmarkWork();
        doneLatch.countDown();
      } catch (Exception e) {
        e.printStackTrace();
      }
    }

    protected void doBenchmarkWork() throws Exception {
      Bench bench = this.bench;
      long deadline = this.deadline;
      do {
        runCycles(bench, 100);
      } while (System.currentTimeMillis() < deadline);
    }
  }
}
