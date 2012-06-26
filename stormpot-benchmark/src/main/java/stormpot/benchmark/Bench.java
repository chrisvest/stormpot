package stormpot.benchmark;

import java.util.Arrays;

import jsr166e.LongAdder;
import jsr166e.LongMaxUpdater;

public abstract class Bench {
  private static final String REPORT_MSG = System.getProperty("report.msg");

  public abstract void primeWithSize(int size, long objTtlMillis) throws Exception;
  public abstract Object claim() throws Exception;
  public abstract void release(Object object) throws Exception;
  
  public void claimAndRelease() throws Exception {
    Object obj = claim();
    assert obj != null: "The pool gave me a sad null.";
    release(obj);
  }
  
  private final LongAdder trials = new LongAdder(); // aka. powerSum0
  private final LongAdder timeSum = new LongAdder(); // aka. powerSum1
  private final LongAdder powerSum2 = new LongAdder();
  private final LongMaxUpdater timeMin = new LongMaxUpdater();
  private final LongMaxUpdater timeMax = new LongMaxUpdater();
  private volatile long period;
  
  public final void recordTime(long time) {
    trials.increment();
    timeSum.add(time);
    powerSum2.add(time * time);
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
    long timeSum = this.timeSum.sum();
    long powerSum2 = this.powerSum2.sum();
    double stdDev = stdDev(trials, timeSum, powerSum2);
    long timeMax = this.timeMax.max();
    long timeMin = Long.MAX_VALUE - this.timeMin.max();
    double cyclesPerSec = (1000.0 / period) * trials;
    double timeMean = ((double) timeSum) / trials;
    
    System.out.print(String.format(REPORT_MSG,
        name, trials, period, cyclesPerSec, timeMax, timeMean, timeMin, stdDev));
  }
  
  private double stdDev(double s0, double s1, double s2) {
    // http://en.wikipedia.org/wiki/Standard_deviation#Rapid_calculation_methods
    return Math.sqrt(s0 * s2 - s1 * s1) / s0;
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
