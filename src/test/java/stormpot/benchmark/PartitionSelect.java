package stormpot.benchmark;

import java.util.concurrent.atomic.AtomicInteger;

import com.google.caliper.SimpleBenchmark;

/*

[cvh: stormpot (master)]$ ./benchmark stormpot.benchmark.PartitionSelect
(building classpath... done)
 0% Scenario{vm=java, trial=0, benchmark=ModuloConst} 1.63 ns; ?=0.00 ns @ 3 trials
14% Scenario{vm=java, trial=0, benchmark=ModuloThreadId} 2.71 ns; ?=0.00 ns @ 3 trials
29% Scenario{vm=java, trial=0, benchmark=ModuloAtomicCount} 19.57 ns; ?=0.00 ns @ 3 trials
43% Scenario{vm=java, trial=0, benchmark=ModuloScalableCounter} 18.72 ns; ?=0.00 ns @ 3 trials
57% Scenario{vm=java, trial=0, benchmark=ThreadLocalRef} 7.90 ns; ?=0.01 ns @ 3 trials
71% Scenario{vm=java, trial=0, benchmark=ModuloIdentityHashThread} 6.71 ns; ?=0.00 ns @ 3 trials
86% Scenario{vm=java, trial=0, benchmark=ModuloIdentityHashNewObject} 140.70 ns; ?=0.21 ns @ 3 trials

                  benchmark     ns linear runtime
                ModuloConst   1.63 =
             ModuloThreadId   2.71 =
          ModuloAtomicCount  19.57 ====
      ModuloScalableCounter  18.72 ===
             ThreadLocalRef   7.90 =
   ModuloIdentityHashThread   6.71 =
ModuloIdentityHashNewObject 140.70 ==============================

vm: java
trial: 0
[cvh: stormpot (master)]$

Turns out it is *really* fast to get the ID of the current thread, in Java.

 */
public class PartitionSelect extends SimpleBenchmark {
  private static final int partitions = 10;
  private static final int base = -358431684;
  private static volatile int counter;
  private static final ThreadLocal<Integer> tlsInt = new ThreadLocal<Integer>();
  private static int randInt;
  
  @Override
  protected void setUp() {
    counter = 0;
    tlsInt.set(new Integer(1));
    randInt = (int) (Math.random() * 1000.0);
  }
  
  public int timeModuloConst(int reps) {
    int result = base;
    for (int i = 0; i < reps; i++) {
      result ^= result + randInt % partitions;
    }
    return result;
  }
  
  public int timeModuloThreadId(int reps) {
    int result = base;
    for (int i = 0; i < reps; i++) {
      result ^= result + Thread.currentThread().getId() % partitions;
    }
    return result;
  }
  
  /*
   * The AtomicInteger based tests are flawed in that the CASes are never
   * contended. So the increments never see a CAS failure.
   */
  public int timeModuloAtomicCount(int reps) {
    AtomicInteger counter = new AtomicInteger();
    int result = base;
    for (int i = 0; i < reps; i++) {
      result ^= result + counter.incrementAndGet() % partitions;
    }
    return result;
  }
  
  /*
   * The Scalable Counter based tests are flawed in that there is never any
   * contention. So the result is always accurate, and the cache-line never
   * have to do a hand-over, so it will remain exclusive to the current CPU
   * core. The x86 CPUs knows how to do this fast, presumably.
   */
  public int timeModuloScalableCounter(int reps) {
    int result = base;
    for (int i = 0; i < reps; i++) {
      result ^= result + (++counter) % partitions;
    }
    return result;
  }
  
  public int timeThreadLocalRef(int reps) {
    int result = base;
    for (int i = 0; i < reps; i++) {
      result ^= result + tlsInt.get().intValue();
    }
    return result;
  }
  
  public int timeModuloIdentityHashThread(int reps) {
    int result = base;
    for (int i = 0; i < reps; i++) {
      result ^= result +
          System.identityHashCode(Thread.currentThread()) % partitions;
    }
    return result;
  }
  
  public int timeModuloIdentityHashNewObject(int reps) {
    int result = base;
    for (int i = 0; i < reps; i++) {
      result ^= result + System.identityHashCode(new Object()) % partitions;
    }
    return result;
  }
}
