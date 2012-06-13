package stormpot.benchmark;

public abstract class Bench {
  public abstract void primeWithSize(int size) throws Exception;
  public abstract Object claim() throws Exception;
  public abstract void release(Object object) throws Exception;
  public abstract void claimAndRelease() throws Exception;
  
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
    String name = getName();
    double cyclesPerSec = (1000.0 / period) * trials;
    double timeMean = ((double) timeSum) / trials;
    
    String str = "Benchmark: %s\t" +
    		"%s trials\t" +
    		"%.1f claim+release/sec\t" +
    		"latency(max, mean, min) = " +
    		"(% 3d, %.6f, %s) in millis.\n";
    System.out.printf(
        str, name, trials, cyclesPerSec, timeMax, timeMean, timeMin);
  }
  
  public final String getName() {
    return getClass().getSimpleName();
  }
}
