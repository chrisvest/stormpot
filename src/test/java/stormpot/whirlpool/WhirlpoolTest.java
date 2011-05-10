package stormpot.whirlpool;

import static stormpot.UnitKit.*;

import java.util.concurrent.Callable;
import java.util.concurrent.TimeUnit;

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
    Thread thread = fork($claimTrafficGenerator(pool, 1));
    fork($delayedReleases(objs, 20, TimeUnit.MILLISECONDS));
    waitForThreadState(thread, Thread.State.RUNNABLE);
    
    // this must return before the test times out:
    pool.claim();
    System.out.println(thread.getState());
    thread.interrupt();
  }

  private Callable $claimTrafficGenerator(
      final Whirlpool pool, final long timeout) {
    return new Callable() {
      public Object call() throws Exception {
        try {
          for (;;) {
            // runs until interrupted
            Poolable obj = pool.claim(timeout, TimeUnit.MILLISECONDS);
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
  
  // TODO must prefer to wake up expired waiters over replying
}
