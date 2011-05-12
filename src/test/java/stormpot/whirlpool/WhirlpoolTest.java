/*
 * Copyright 2011 Chris Vest
 * 
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 * 
 *      http://www.apache.org/licenses/LICENSE-2.0
 * 
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
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
    Whirlpool pool = givenDelayedReleaseToContendedPool(1);
    // this must return before the test times out:
    pool.claim();
    pool.shutdown();
  }

  private Whirlpool givenDelayedReleaseToContendedPool(
      long claimTrafficTimeoutMs) throws InterruptedException {
    config.setSize(1);
    Whirlpool pool = new Whirlpool(config);
    Poolable[] objs = new Poolable[] {pool.claim()};
    Thread thread = fork($claimTrafficGenerator(pool, claimTrafficTimeoutMs));
    fork($delayedReleases(objs, 20, TimeUnit.MILLISECONDS));
    waitForThreadState(thread, Thread.State.RUNNABLE);
    return pool;
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
        } catch (Exception _) {
          // ignore it
        }
        return null;
      }
    };
  }
  
  @Test(timeout = 300) public void
  blockedThreadsMustMakeProgressOverExpiredWaiters() throws Exception {
    // Github Issue #15
    Whirlpool pool = givenDelayedReleaseToContendedPool(-10);
    // this must return before the test times out:
    pool.claim();
    pool.shutdown();
  }
}
