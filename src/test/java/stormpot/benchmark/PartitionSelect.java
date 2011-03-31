package stormpot.benchmark;

import java.util.concurrent.atomic.AtomicInteger;

import com.google.caliper.SimpleBenchmark;

/*

[cvh: stormpot (master)]$ ./benchmark stormpot.benchmark.PartitionSelect
 0% Scenario{vm=java, trial=0, benchmark=ModuloConst} 1.43 ns; σ=0.00 ns @ 3 trials
 8% Scenario{vm=java, trial=0, benchmark=AndShiftConst} 1.03 ns; σ=0.00 ns @ 3 trials
15% Scenario{vm=java, trial=0, benchmark=ModuloThreadId} 2.94 ns; σ=0.02 ns @ 3 trials
23% Scenario{vm=java, trial=0, benchmark=AndShiftThreadId} 1.03 ns; σ=0.00 ns @ 3 trials
31% Scenario{vm=java, trial=0, benchmark=ModuloAtomicCount} 23.11 ns; σ=0.06 ns @ 3 trials
38% Scenario{vm=java, trial=0, benchmark=AndShiftAtomicCount} 20.66 ns; σ=0.05 ns @ 3 trials
46% Scenario{vm=java, trial=0, benchmark=ModuloScalableCounter} 23.00 ns; σ=0.03 ns @ 3 trials
54% Scenario{vm=java, trial=0, benchmark=AndShiftScalableCounter} 18.80 ns; σ=0.04 ns @ 3 trials
62% Scenario{vm=java, trial=0, benchmark=ThreadLocalRef} 7.04 ns; σ=0.01 ns @ 3 trials
69% Scenario{vm=java, trial=0, benchmark=ModuloIdentityHashThread} 5.57 ns; σ=0.06 ns @ 10 trials
77% Scenario{vm=java, trial=0, benchmark=AndShiftIdentityHashThread} 2.84 ns; σ=0.03 ns @ 4 trials
85% Scenario{vm=java, trial=0, benchmark=ModuloIdentityHashNewObject} 168.91 ns; σ=0.45 ns @ 3 trials
92% Scenario{vm=java, trial=0, benchmark=AndShiftIdentityHashNewObject} 167.36 ns; σ=0.93 ns @ 3 trials

                    benchmark     ns linear runtime
                  ModuloConst   1.43 =
                AndShiftConst   1.03 =
               ModuloThreadId   2.94 =
             AndShiftThreadId   1.03 =
            ModuloAtomicCount  23.11 ====
          AndShiftAtomicCount  20.66 ===
        ModuloScalableCounter  23.00 ====
      AndShiftScalableCounter  18.80 ===
               ThreadLocalRef   7.04 =
     ModuloIdentityHashThread   5.57 =
   AndShiftIdentityHashThread   2.84 =
  ModuloIdentityHashNewObject 168.91 ==============================
AndShiftIdentityHashNewObject 167.36 =============================

vm: java
trial: 0
[cvh: stormpot (master)]$ 

I had to hack Caliper to accept execution times of less than 0.1 nanosecond.
Turns out it is *really* fast to get the ID of the current thread, in Java.

 */
public class PartitionSelect extends SimpleBenchmark {
  private static final int partitions = 10;
  private static final int mask =
    ~(0xFFFFFFFF << (32 - Integer.numberOfLeadingZeros(partitions)));
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
      result ^= result + n < partitions? n : n >>> 1;
    }
    return result;
  }
  
  /*
   * The thread-id based tests are flawed in that the compiler sees through
   * them, and constant-folds them into oblivion.
   */
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
      result ^= result + n < partitions? n : n >>> 1;
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
      result ^= result + n < partitions? n : n >>> 1;
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
      result ^= result + n < partitions? n : n >>> 1;
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
      result ^= result + n < partitions? n : n >>> 1;
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
      result ^= result + n < partitions? n : n >>> 1;
    }
    return result;
  }
}
