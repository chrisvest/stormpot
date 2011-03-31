package stormpot.benchmark;

import java.util.Arrays;
import java.util.concurrent.atomic.AtomicInteger;

import com.google.caliper.SimpleBenchmark;

/*

[cvh: stormpot (master)]$ ./benchmark stormpot.benchmark.PartitionSelect
 0% Scenario{vm=java, trial=0, benchmark=ModuloConst} 1.42 ns; σ=0.00 ns @ 3 trials
 8% Scenario{vm=java, trial=0, benchmark=AndShiftConst} 1.03 ns; σ=0.01 ns @ 3 trials
15% Scenario{vm=java, trial=0, benchmark=ModuloThreadId} 2.95 ns; σ=0.01 ns @ 3 trials
23% Scenario{vm=java, trial=0, benchmark=AndShiftThreadId} 1.03 ns; σ=0.01 ns @ 3 trials
31% Scenario{vm=java, trial=0, benchmark=ModuloAtomicCount} 23.03 ns; σ=0.22 ns @ 5 trials
38% Scenario{vm=java, trial=0, benchmark=AndShiftAtomicCount} 21.29 ns; σ=0.08 ns @ 3 trials
46% Scenario{vm=java, trial=0, benchmark=ModuloScalableCounter} 22.18 ns; σ=0.02 ns @ 3 trials
54% Scenario{vm=java, trial=0, benchmark=AndShiftScalableCounter} 18.93 ns; σ=0.02 ns @ 3 trials
62% Scenario{vm=java, trial=0, benchmark=ThreadLocalRef} 6.98 ns; σ=0.01 ns @ 3 trials
69% Scenario{vm=java, trial=0, benchmark=ModuloIdentityHashThread} 5.56 ns; σ=0.01 ns @ 3 trials
77% Scenario{vm=java, trial=0, benchmark=AndShiftIdentityHashThread} 3.29 ns; σ=0.01 ns @ 3 trials
85% Scenario{vm=java, trial=0, benchmark=ModuloIdentityHashNewObject} 166.37 ns; σ=0.52 ns @ 3 trials
92% Scenario{vm=java, trial=0, benchmark=AndShiftIdentityHashNewObject} 166.86 ns; σ=0.21 ns @ 3 trials

                    benchmark     ns linear runtime
                  ModuloConst   1.42 =
                AndShiftConst   1.03 =
               ModuloThreadId   2.95 =
             AndShiftThreadId   1.03 =
            ModuloAtomicCount  23.03 ====
          AndShiftAtomicCount  21.29 ===
        ModuloScalableCounter  22.18 ===
      AndShiftScalableCounter  18.93 ===
               ThreadLocalRef   6.98 =
     ModuloIdentityHashThread   5.56 =
   AndShiftIdentityHashThread   3.29 =
  ModuloIdentityHashNewObject 166.37 =============================
AndShiftIdentityHashNewObject 166.86 ==============================

vm: java
trial: 0
[cvh: stormpot (master)]$

I had to hack Caliper to accept execution times of less than 0.1 nanosecond.
Turns out it is *really* fast to get the ID of the current thread, in Java.

 */
public class PartitionSelect extends SimpleBenchmark {
  private static final int partitions = 10;
  private static final int nlz = Integer.numberOfLeadingZeros(partitions);
  private static final int nob = 32 - nlz; // number of one bits
  private static final int mask = ~(0xFFFFFFFF << nob);
  private static final int base = -358431684;
  private static volatile int counter;
  private static final ThreadLocal<Integer> tlsInt = new ThreadLocal<Integer>();
  private static int randInt;
  
  @Override
  protected void setUp() {
    counter = 0;
    tlsInt.set(new Integer(1));
    randInt = (int) Math.random();
  }
  
  public int timeModuloConst(int reps) {
    int result = base;
    for (int i = 0; i < reps; i++) {
      result ^= result + randInt % partitions;
    }
    return result;
  }
  
  public int timeAndShiftConst(int reps) {
    int result = base;
    for (int i = 0; i < reps; i++) {
      int n = randInt & mask;
      n = n < partitions? n : ((i >> nob) ^ n) & mask;
      result ^= result + n < partitions? n : ((~n) & mask);
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
  
  public int timeAndShiftThreadId(int reps) {
    int result = base;
    for (int i = 0; i < reps; i++) {
      // This test is flawed in that any bias introduced by the varying values
      // of thread-id, is not observed.
      // In fact, the JIT might optimistically eliminate the branch because of
      // this.
      int n = ((int) Thread.currentThread().getId()) & mask;
      n = n < partitions? n : ((i >> nob) ^ n) & mask;
      result ^= result + n < partitions? n : ((~n) & mask);
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
  
  public int timeAndShiftAtomicCount(int reps) {
    AtomicInteger counter = new AtomicInteger();
    int result = base;
    for (int i = 0; i < reps; i++) {
      int n = counter.incrementAndGet() & mask;
      n = n < partitions? n : ((i >> nob) ^ n) & mask;
      result ^= result + n < partitions? n : ((~n) & mask);
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
  
  public int timeAndShiftScalableCounter(int reps) {
    int result = base;
    for (int i = 0; i < reps; i++) {
      int n = (++counter) & mask;
      n = n < partitions? n : ((i >> nob) ^ n) & mask;
      result ^= result + n < partitions? n : ((~n) & mask);
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
  
  public int timeAndShiftIdentityHashThread(int reps) {
    int result = base;
    for (int i = 0; i < reps; i++) {
      int n = System.identityHashCode(Thread.currentThread()) & mask;
      n = n < partitions? n : ((i >> nob) ^ n) & mask;
      result ^= result + n < partitions? n : ((~n) & mask);
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
  
  public int timeAndShiftIdentityHashNewObject(int reps) {
    int result = base;
    for (int i = 0; i < reps; i++) {
      int n = System.identityHashCode(new Object()) & mask;
      n = n < partitions? n : ((i >> nob) ^ n) & mask;
      result ^= result + n < partitions? n : ((~n) & mask);
    }
    return result;
  }
  
  public static void main(String[] args) {
    int iters = 1000000;
    for (int parts = 1; parts < 20; parts++) {
      int[] pool = new int[parts];
      int nlz = Integer.numberOfLeadingZeros(parts);
      int nob = 32 - nlz; // number of one bits
      int m = ~(0xFFFFFFFF << nob);
      for (int i = 0; i < iters; i++) {
        int n = i & m;
        n = n < parts? n : ((i >> nob) ^ n) & m;
        n = n < parts? n : ((~n) & m);
        pool[n]++;
      }
      System.out.println(Arrays.toString(pool));
    }
  }
}
