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
package stormpot.benchmark;

import stormpot.Pool;
import stormpot.Poolable;
import stormpot.UnitKit;

import com.google.caliper.Param;

/*
 * Sadly, it seems that Caliper benchmarks can't extend one another :(
 */
public class ContendedPoolSpin extends PoolSpin {
  
  @Param({"1", "2", "4"}) int threads;
  @Param({"0", "1", "2"}) int poolType;
  
  Thread[] contenders;
  
  @Override
  protected void setUp() throws Exception {
    super.poolType = poolType;
    super.setUp();
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
      Poolable obj = null;
      try {
        for (;;) {
          try {
            obj = pool.claim();
          } finally {
            if (obj != null) {
              obj.release();
            }
            obj = null;
          }
        }
      } catch (Exception e) {
        // ... aaand, we're done.
      }
    }
  }
}
