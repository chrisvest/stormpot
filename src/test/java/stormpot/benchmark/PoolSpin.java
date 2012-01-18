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

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.LockSupport;

import stormpot.Allocator;
import stormpot.Config;
import stormpot.GenericPoolable;
import stormpot.LifecycledPool;
import stormpot.Pool;
import stormpot.Poolable;
import stormpot.Slot;
import stormpot.basicpool.BasicPoolFixture;
import stormpot.qpool.QPoolFixture;

import com.google.caliper.Param;
import com.google.caliper.SimpleBenchmark;

public class PoolSpin extends SimpleBenchmark {
  private final class SlowAllocator implements Allocator<GenericPoolable> {
    private final List<GenericPoolable> allocated =
      Collections.synchronizedList(new ArrayList<GenericPoolable>());
    private final List<GenericPoolable> deallocated =
      Collections.synchronizedList(new ArrayList<GenericPoolable>());
    private final long workMs;

    public SlowAllocator(long workMs) {
      this.workMs = workMs;
    }
    
    public GenericPoolable allocate(Slot slot) {
      long deadline = System.currentTimeMillis() + workMs;
      LockSupport.parkUntil(deadline);
      GenericPoolable obj = new GenericPoolable(slot);
      allocated.add(obj);
      return obj;
    }

    public void deallocate(GenericPoolable poolable) {
      poolable.deallocated = true;
      deallocated.add(poolable);
    }
  }

  @Param({"10"}) int size = 10;
  @Param({"10"}) long work = 10;
  @Param({"0", "1"}) int poolType;
  @Param({"10000"}) long ttl = 10000;

  protected Pool<GenericPoolable> pool;
  
  @Override
  protected void setUp() throws Exception {
    Config<GenericPoolable> config = new Config<GenericPoolable>();
    config.setAllocator(new SlowAllocator(work));
    config.setSize(size);
    config.setTTL(ttl, TimeUnit.MILLISECONDS);
    pool = (poolType == 0? new BasicPoolFixture() : new QPoolFixture()).initPool(config);
    // Give the pool 500 ms to boot up any threads it might need
    LockSupport.parkNanos(500000000);
  }
  
  public int timeClaimReleaseSpin(int reps) throws Exception {
    int result = 1235789;
    for (int i = 0; i < reps; i++) {
      Poolable obj = claim(pool);
      result ^= obj.hashCode();
      obj.release();
    }
    return result;
  }

  static GenericPoolable claim(Pool<GenericPoolable> pool)
  throws InterruptedException {
    GenericPoolable obj = pool.claim();
    obj.lastClaimBy = Thread.currentThread();
    return obj;
  }

  @Override
  protected void tearDown() throws Exception {
    if (pool instanceof LifecycledPool) {
      LifecycledPool<GenericPoolable> p = (LifecycledPool<GenericPoolable>) pool;
      p.shutdown().await();
    }
  }
}
