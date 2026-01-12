/*
 * Copyright © Chris Vest (mr.chrisvest@gmail.com)
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

import org.jctools.queues.MpscArrayQueue;
import org.openjdk.jmh.annotations.Benchmark;
import org.openjdk.jmh.annotations.Fork;
import org.openjdk.jmh.annotations.Level;
import org.openjdk.jmh.annotations.Param;
import org.openjdk.jmh.annotations.Scope;
import org.openjdk.jmh.annotations.Setup;
import org.openjdk.jmh.annotations.State;
import org.openjdk.jmh.annotations.TearDown;
import org.openjdk.jmh.infra.Blackhole;
import org.openjdk.jmh.infra.Control;
import org.openjdk.jmh.runner.Runner;
import org.openjdk.jmh.runner.RunnerException;
import org.openjdk.jmh.runner.options.Options;
import org.openjdk.jmh.runner.options.OptionsBuilder;
import org.openjdk.jmh.runner.options.TimeValue;
import stormpot.Completion;
import stormpot.Expiration;
import stormpot.Pool;
import stormpot.Timeout;

import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

@Fork(jvmArgsAppend = {"-XX:+UnlockDiagnosticVMOptions", "-XX:+DebugNonSafepoints"})
@State(Scope.Benchmark)
public class ThreadTransfer {
  private static final stormpot.Timeout timeout = new Timeout(10, TimeUnit.MILLISECONDS);

  @Param({"2048"})
  public int poolSize;
  @Param({"100"})
  public int cpuTokens;

  Pool<GenericPoolable> pool;
  MpscArrayQueue<GenericPoolable> queue;
  Thread consumer;

  @Setup
  public void setUp() {
    pool = Pool.fromInline(new GenericAllocator())
            .setSize(poolSize)
            .setExpiration(Expiration.never())
            .setPreciseLeakDetectionEnabled(false)
            .setOptimizeForReducedMemoryUsage(false)
            .build();
    queue = new MpscArrayQueue<>(poolSize * 2);
  }

  @TearDown
  public void tearDown() throws InterruptedException {
    Completion completion = pool.shutdown();
    queue.drain(GenericPoolable::release);
    completion.await(timeout);
  }

  @Setup(Level.Iteration)
  public void startConsumer(Control infraControl) {
    AtomicBoolean consumerStart = new AtomicBoolean();
    consumer = Thread.ofPlatform().name("releaser").start(() -> {
      consumerStart.set(true);
      while (!infraControl.startMeasurement) {
        Thread.onSpinWait();
      }
      while (!infraControl.stopMeasurement) {
        queue.drain(GenericPoolable::release, 16);
      }
    });
    while (!consumerStart.get()) {
      Thread.onSpinWait();
    }
  }

  @TearDown(Level.Iteration)
  public void stopConsumer() throws InterruptedException {
    consumer.join();
  }

  @Benchmark
  public void claim() throws InterruptedException {
    GenericPoolable obj = pool.claim(timeout);
    if (obj != null) {
      queue.offer(obj);
    }
    Blackhole.consumeCPU(cpuTokens);
  }

  public static void main(String[] args) throws RunnerException {
    Options opt = new OptionsBuilder()
            .include(ThreadTransfer.class.getSimpleName())
            .measurementTime(TimeValue.seconds(1))
            .measurementIterations(10)
            .warmupTime(TimeValue.seconds(1))
            .warmupIterations(20)
//            .forks(0) // For debugging.
            .threads(4)
            .build();

    new Runner(opt).run();
  }
}
