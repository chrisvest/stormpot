/*
 * Copyright © 2011-2024 Chris Vest (mr.chrisvest@gmail.com)
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package stormpot.benchmarks;

import org.openjdk.jmh.annotations.Benchmark;
import org.openjdk.jmh.annotations.Param;
import org.openjdk.jmh.annotations.Scope;
import org.openjdk.jmh.annotations.Setup;
import org.openjdk.jmh.annotations.State;
import org.openjdk.jmh.annotations.TearDown;
import org.openjdk.jmh.annotations.Threads;
import stormpot.Expiration;
import stormpot.Pool;
import stormpot.PoolTap;
import stormpot.Timeout;

import java.util.concurrent.TimeUnit;

@Threads(12)
@State(Scope.Benchmark)
public class ClaimRelease {
  private static final stormpot.Timeout timeout = new Timeout(10, TimeUnit.SECONDS);

  @Param({"20"})
  public int poolSize;
  Pool<GenericPoolable> pool;

  @Setup
  public void setUp() {
    pool = Pool.from(new GenericAllocator())
            .setSize(poolSize)
            .setExpiration(Expiration.never())
            .setBackgroundExpirationEnabled(false)
            .setPreciseLeakDetectionEnabled(false)
            .build();
  }

  @State(Scope.Thread)
  public static class PerThread {
    PoolTap<GenericPoolable> threadLocal;
    PoolTap<GenericPoolable> vthreadSafe;
    PoolTap<GenericPoolable> sequential;

    @Setup
    public void setUp(ClaimRelease bench) {
      threadLocal = bench.pool.getThreadSafeTap();
      vthreadSafe = bench.pool.getVirtualThreadSafeTap();
      sequential = bench.pool.getSingleThreadedTap();
    }
  }

  @TearDown
  public void tearDown() throws InterruptedException {
    pool.shutdown().await(timeout);
  }

  @Benchmark
  public void threadSafe(PerThread state) throws InterruptedException {
    state.threadLocal.claim(timeout).release();
  }

  @Benchmark
  public void virtThreadSafe(PerThread state) throws InterruptedException {
    state.vthreadSafe.claim(timeout).release();
  }

  @Benchmark
  public void sequential(PerThread state) throws InterruptedException {
    state.sequential.claim(timeout).release();
  }
}
