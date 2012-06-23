package stormpot.benchmark;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.locks.LockSupport;

public class MultiThreadedThroughputBenchmark extends Benchmark {
  private static final int THREAD_COUNT = Integer.getInteger("thread.count",
        Runtime.getRuntime().availableProcessors());

  private WorkerThread[] threads;
  private volatile CountDownLatch doneLatch;
  
  @Override
  protected String getBenchmarkName() {
    return "Stormpot Multi-Threaded Throughput Benchmark";
  }

  @Override
  protected int[] warmupCycles() {
    return new int[] {1, 13, 1, 8, 1, 1};
  }

  @Override
  protected void warmup(Bench bench, int steps) throws Exception {
    System.out.println(
        "Warming up " + bench.getName() + " with " + steps + " steps.");
    for (int i = 0; i < steps; i++) {
      prepareAndRunBenchmark(bench, 1000);
      System.out.printf("%02d/%s.", i + 1, steps);
    }
    System.out.println("\ndone.");
  }

  @Override
  protected void beforeBenchmark(Bench bench, long trialTimeMillis) {
    super.beforeBenchmark(bench, trialTimeMillis);
    ensureThreadsCreated();
    doneLatch = new CountDownLatch(THREAD_COUNT);
    for (WorkerThread th : threads) {
      th.bench = bench;
      th.doneLatch = doneLatch;
    }
  }

  protected void ensureThreadsCreated() {
    if (threads == null) {
      threads = new WorkerThread[THREAD_COUNT];
      for (int i = 0; i < THREAD_COUNT; i++) {
        threads[i] = new WorkerThread();
        threads[i].start();
      }
    }
  }

  @Override
  protected void benchmark(Bench bench, long trialTimeMillis) throws Exception {
    long start = System.currentTimeMillis();
    long deadline = start + trialTimeMillis;
    start(threads, deadline);
    doneLatch.await();
    long end = System.currentTimeMillis();
    bench.recordPeriod(end - start);
  }
  
  private void start(WorkerThread[] threads, long deadline) {
    for (WorkerThread th : threads) {
      th.deadline = deadline;
      LockSupport.unpark(th);
    }
  }

  private static class WorkerThread extends Thread {
    private static final AtomicInteger counter = new AtomicInteger();
    public Bench bench;
    public volatile long deadline;
    public volatile CountDownLatch doneLatch;
    
    public WorkerThread() {
      super("WorkerThread-" + counter.incrementAndGet());
      setDaemon(true);
    }
    
    @Override
    public void run() {
      try {
        runInterruptibly();
      } catch (InterruptedException e) {
        // and now we're done...
      } catch (Exception e) {
        e.printStackTrace();
      }
    }

    private void runInterruptibly() throws Exception {
      for (;;) {
        waitForStartingSignal();
        doBenchmarkWork();
      }
    }

    private void waitForStartingSignal() {
      LockSupport.park();
    }

    protected void doBenchmarkWork() throws Exception {
      CountDownLatch doneLatch = this.doneLatch;
      Bench bench = this.bench;
      long deadline = this.deadline;
      do {
        runCycles(bench, 100);
      } while (System.currentTimeMillis() < deadline);
      doneLatch.countDown();
    }
  }
}
