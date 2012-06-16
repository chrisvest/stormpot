package stormpot.benchmark;

import java.util.Arrays;

public abstract class Bench {
  public abstract void primeWithSize(int size, long objTtlMillis) throws Exception;
  public abstract Object claim() throws Exception;
  public abstract void release(Object object) throws Exception;
  
  public void claimAndRelease() throws Exception {
    release(claim());
  }
  
  private int trials;
  private long timeSum;
  private long timeMin = Long.MAX_VALUE;
  private long timeMax = Long.MIN_VALUE;
  private long period;
  
  public final void recordTime(long time) {
    trials++;
    timeSum += time;
    timeMin = Math.min(timeMin, time);
    timeMax = Math.max(timeMax, time);
  }
  
  public final void recordPeriod(long period) {
    this.period = period;
  }
  
  public final void reset() {
    trials = 0;
    timeSum = 0;
    timeMin = Long.MAX_VALUE;
    timeMax = Long.MIN_VALUE;
    period = 0;
  }
  
  public final void report() {
    String name = computeFixedLengthName(20);
    double cyclesPerSec = (1000.0 / period) * trials;
    double timeMean = ((double) timeSum) / trials;
    
    String str = "Benchmark: %s " +
    		"%7d trials in %3d ms. " +
    		"%7.0f claim+release/sec. " +
    		"latency(max, mean, min) = " +
    		"(%2d, %.6f, %s) in millis.\n";
    System.out.printf(
        str, name, trials, period, cyclesPerSec, timeMax, timeMean, timeMin);
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
