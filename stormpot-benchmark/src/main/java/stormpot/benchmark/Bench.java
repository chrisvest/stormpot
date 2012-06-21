package stormpot.benchmark;

import java.util.Arrays;

import jsr166e.LongAdder;
import jsr166e.LongMaxUpdater;

public abstract class Bench {
  private static final String DEFAULT_REPORT_MSG = "Benchmark: %s " +
  		"%7d trials in %3d ms. " +
  		"%7.0f claim+release/sec. " +
  		"latency(max, mean, min) = " +
  		"(%3d, %.6f, %s) in millis.\n";
  private static final String REPORT_MSG = System.getProperty(
      "report.msg", DEFAULT_REPORT_MSG);

  public abstract void primeWithSize(int size, long objTtlMillis) throws Exception;
  public abstract Object claim() throws Exception;
  public abstract void release(Object object) throws Exception;
  
  public void claimAndRelease() throws Exception {
    release(claim());
  }
  
  private final LongAdder trials = new LongAdder();
  private final LongAdder timeSum = new LongAdder();
  private final LongMaxUpdater timeMin = new LongMaxUpdater();
  private final LongMaxUpdater timeMax = new LongMaxUpdater();
  private volatile long period;
  
  public final void recordTime(long time) {
    trials.increment();
    timeSum.add(time);
    timeMin.update(Long.MAX_VALUE - time);
    timeMax.update(time);
  }
  
  public final void recordPeriod(long period) {
    this.period = period;
  }
  
  public final void reset() {
    trials.reset();
    timeSum.reset();
    timeMin.reset();
    timeMax.reset();
    period = 0;
  }
  
  public final void report() {
    String name = computeFixedLengthName(20);
    long trials = this.trials.sum();
    long timeMax = this.timeMax.max();
    long timeMin = Long.MAX_VALUE - this.timeMin.max();
    double timeSum = this.timeSum.sum();
    double cyclesPerSec = (1000.0 / period) * trials;
    double timeMean = timeSum / trials;
    
    System.out.printf(REPORT_MSG,
        name, trials, period, cyclesPerSec, timeMax, timeMean, timeMin);
  }
  
  private String computeFixedLengthName(int length) {
    char[] nameCs = getName().toCharArray();
    char[] nameField = new char[length];
    Arrays.fill(nameField, ' ');
    for (int i = 0; i < nameField.length && i < nameCs.length; i++) {
      nameField[i] = nameCs[i];
    }
    return String.copyValueOf(nameField);
  }
  
  public String getName() {
    return getClass().getSimpleName();
  }
}
