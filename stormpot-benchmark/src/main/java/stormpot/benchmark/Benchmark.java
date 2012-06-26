package stormpot.benchmark;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Random;

public abstract class Benchmark {
  private static final Random rnd = new Random();
  private static final int SIZE = Integer.getInteger("pool.size");
  private static final long TRIAL_TIME_MILLIS = Long.getLong("trial.time");
  private static final long OBJ_TTL_MILLIS = Long.getLong("obj.ttl");

  protected static Bench[] buildPoolList() {
    List<String> pools = Arrays.asList(System.getProperty("pools").split(","));
    List<Bench> benches = new ArrayList<Bench>();
    if (pools.contains("queue")) {
      benches.add(new QueuePoolBench());
    }
    if (pools.contains("stack")) {
      benches.add(new CmnsStackPoolBench());
    }
    if (pools.contains("generic")) {
      benches.add(new CmnsGenericObjPoolBench());
    }
    if (pools.contains("furious")) {
      benches.add(new FuriousBench());
    }
    return benches.toArray(new Bench[benches.size()]);
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

  public static long runCycles(Bench bench, int cycles) throws Exception {
    long start;
    long end = 0;
    for (int i = 0; i < cycles; i++) {
      start = System.currentTimeMillis();
      bench.claimAndRelease();
      end = System.currentTimeMillis();
      bench.recordTime(end - start);
    }
    return end;
  }

  protected abstract String getBenchmarkName();

  protected abstract void benchmark(Bench bench, long trialTimeMillis) throws Exception;

  public void run() {
    System.out.println(getBenchmarkName());
    try {
      runBenchmark();
    } catch (Exception e) {
      e.printStackTrace();
    }
  }

  private void runBenchmark() throws Exception {
    Bench[] pools = buildPoolList();
    
    prime(pools, SIZE, OBJ_TTL_MILLIS);
    warmup(pools);
    trial(pools);
  }

  private void warmup(Bench[] pools) throws Exception {
    System.out.println("Warming up pools...");
    for (int cycles : warmupCycles()) {
      for (Bench pool : pools) {
        warmup(pool, cycles);
      }
    }
    System.out.println("Warmup done.");
  }
  
  protected abstract int[] warmupCycles();

  protected void warmup(Bench bench, int steps) throws Exception {
    System.out.println(
        "Warming up " + bench.getName() + " with " + steps + "K steps.");
    for (int i = 0; i < steps; i++) {
      for (int j = 0; j < 1000; j++) {
        prepareAndRunBenchmark(bench, 1);
      }
      System.out.printf("%02d/%02d.", i + 1, steps);
    }
    System.out.println("\ndone.");
  }

  protected void trial(Bench[] pools) throws Exception {
    for (int i = 0; i < 10; i++) {
      shuffle(pools);
      for (Bench pool : pools) {
        trial(pool);
      }
    }
  }

  private void trial(Bench bench) throws Exception {
    Thread.sleep(10);
    prepareAndRunBenchmark(bench, TRIAL_TIME_MILLIS);
    bench.report();
  }

  protected void prepareAndRunBenchmark(Bench bench, long trialTimeMillis)
      throws Exception {
    beforeBenchmark(bench, trialTimeMillis);
    benchmark(bench, trialTimeMillis);
  }

  protected void beforeBenchmark(Bench bench, long trialTimeMillis) {
    bench.reset();
  }
}
