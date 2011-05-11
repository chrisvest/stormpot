package stormpot.benchmark;

import stormpot.Pool;
import stormpot.UnitKit;

import com.google.caliper.Param;

/*
 * Sadly, it seems that Caliper benchmarks can't extend one another :(
 */
public class ContendedPoolSpin extends PoolSpin {
  
  @Param int threads = 2;
  @Param int poolType;
  
  Thread[] contenders;
  
  @Override
  protected void setUp() throws Exception {
    super.setUp();
    super.poolType = poolType;
    contenders = new Thread[threads-1];
    for (int i = 0; i < contenders.length; i++) {
      contenders[i] = new ContenderThread(pool);
      contenders[i].start();
    }
    for (Thread th : contenders) {
      UnitKit.waitForThreadState(th, Thread.State.RUNNABLE);
    }
  }
  
  @Override
  public int timeClaimReleaseSpin(int reps) throws Exception {
    return super.timeClaimReleaseSpin(reps);
  }

  @Override
  protected void tearDown() throws Exception {
    super.tearDown();
    for (Thread th : contenders) {
      th.interrupt();
      try {
        th.join();
      } catch (Exception _) {}
    }
  }
  
  public static class ContenderThread extends Thread {
    private final Pool pool;
    public ContenderThread(Pool pool) {
      this.pool = pool;
    }
    
    @Override
    public void run() {
      try {
        for (;;) {
          pool.claim().release();
        }
      } catch (Exception e) {
        // ... aaand, we're done.
      }
    }
  }
}
