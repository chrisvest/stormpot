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

import org.jctools.queues.SpscArrayQueue;
import org.openjdk.jmh.annotations.Benchmark;
import org.openjdk.jmh.annotations.Fork;
import org.openjdk.jmh.annotations.Group;
import org.openjdk.jmh.annotations.Param;
import org.openjdk.jmh.annotations.Scope;
import org.openjdk.jmh.annotations.Setup;
import org.openjdk.jmh.annotations.State;
import org.openjdk.jmh.annotations.TearDown;
import org.openjdk.jmh.annotations.Threads;
import stormpot.Completion;
import stormpot.Expiration;
import stormpot.Pool;
import stormpot.Timeout;

import java.util.concurrent.TimeUnit;

@Threads(2)
@Fork(jvmArgsAppend = {"-XX:+UnlockDiagnosticVMOptions", "-XX:+DebugNonSafepoints"})
@State(Scope.Benchmark)
public class ThreadTransfer {
  private static final stormpot.Timeout timeout = new Timeout(1, TimeUnit.MILLISECONDS);

  @Param({"2048"})
  public int poolSize;
  Pool<GenericPoolable> pool;
  SpscArrayQueue<GenericPoolable> queue;

  @Setup
  public void setUp() {
    pool = Pool.fromInline(new GenericAllocator())
            .setSize(poolSize)
            .setExpiration(Expiration.never())
            .setPreciseLeakDetectionEnabled(false)
            .setOptimizeForReducedMemoryUsage(false)
            .build();
    queue = new SpscArrayQueue<>(poolSize * 2);
  }

  @TearDown
  public void tearDown() throws InterruptedException {
    Completion completion = pool.shutdown();
    queue.drain(GenericPoolable::release);
    completion.await(timeout);
  }

  @Benchmark
  @Group("claim")
  public void claim() throws InterruptedException {
    GenericPoolable obj = pool.claim(timeout);
    if (obj != null) {
      queue.offer(obj);
    }
  }

  @Benchmark
  @Group("claim")
  public void release() {
    GenericPoolable obj = queue.poll();
    if (obj != null) {
      obj.release();
    }
  }
}
