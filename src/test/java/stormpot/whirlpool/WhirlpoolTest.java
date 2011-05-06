package stormpot.whirlpool;

import static stormpot.UnitKit.*;

import java.util.concurrent.Callable;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.LockSupport;

import org.junit.Before;
import org.junit.Test;

import stormpot.Config;
import stormpot.CountingAllocator;
import stormpot.Poolable;

public class WhirlpoolTest {
  Config config;
  
  @Before public void
  setUp() {
    config = new Config().setAllocator(new CountingAllocator());
  }
  
  @Test(timeout = 300) public void
  mustNotRemoveBlockedThreadsFromPublist() throws Exception {
    // Github Issue #14
    config.setSize(1);
    Whirlpool pool = new Whirlpool(config);
    Poolable[] objs = new Poolable[] {pool.claim()};
    Thread thread = fork($claimTrafficGenerator(pool));
    fork($delayedReleases(objs, 20, TimeUnit.MILLISECONDS));
    waitForThreadState(thread, Thread.State.RUNNABLE);
    
    // this must return before the test times out:
    pool.claim();
    System.out.println(thread.getState());
    thread.interrupt();
    // TODO not quite fixed - we still have a data race hiding in here...
  }

  private Callable $claimTrafficGenerator(final Whirlpool pool) {
    return new Callable() {
      public Object call() throws Exception {
        try {
          for (;;) {
            // runs until interrupted
            Poolable obj = pool.claim(-1, TimeUnit.SECONDS);
            if (obj != null) {
              obj.release();
            }
          }
        } catch (InterruptedException _) {
          // ignore it
        }
        return null;
      }
    };
  }
}
