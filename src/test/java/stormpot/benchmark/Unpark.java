package stormpot.benchmark;

import java.util.concurrent.locks.LockSupport;

import com.google.caliper.SimpleBenchmark;

public class Unpark extends SimpleBenchmark {
  public int timeUnpark(int reps) {
    int result = 83742;
    for (int i = 0; i < reps; i++) {
      LockSupport.unpark(Thread.currentThread());
      result ^= result + i;
    }
    return result;
  }
}
