/*
 * Copyright (C) 2011-2014 Chris Vest (mr.chrisvest@gmail.com)
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *         http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package stormpot.slow;

import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.experimental.theories.DataPoint;
import org.junit.experimental.theories.Theories;
import org.junit.experimental.theories.Theory;
import org.junit.rules.TestRule;
import org.junit.runner.RunWith;
import stormpot.*;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.instanceOf;
import static org.junit.Assert.assertThat;
import static org.junit.Assume.assumeThat;
import static org.junit.internal.matchers.ThrowableMessageMatcher.hasMessage;

@RunWith(Theories.class)
public class PoolIT {
  @Rule public final TestRule failurePrinter = new FailurePrinterTestRule();
  @Rule public final ExecutorTestRule executorFactory = new ExecutorTestRule();

  private static final Timeout longTimeout = new Timeout(1, TimeUnit.MINUTES);

  // Initialised by setUp()
  private CountingAllocator allocator;
  private Config<GenericPoolable> config;
  private ExecutorService executor;

  // Initialised in the tests
  private LifecycledResizablePool<GenericPoolable> pool;

  @DataPoint public static PoolFixture queuePool = new QueuePoolFixture();
  @DataPoint public static PoolFixture blazePool = new BlazePoolFixture();

  @Before
  public void
  setUp() {
    allocator = new CountingAllocator();
    config = new Config<GenericPoolable>().setSize(1).setAllocator(allocator);
    executor = executorFactory.getExecutorService();
  }

  private LifecycledResizablePool<GenericPoolable> lifecycledResizable(PoolFixture fixture) {
    Pool<GenericPoolable> pool = fixture.initPool(config);
    assumeThat(pool, instanceOf(LifecycledResizablePool.class));
    return (LifecycledResizablePool<GenericPoolable>) pool;
  }

  @Test(timeout = 1601)
  @Theory public void
  highContentionMustNotCausePoolLeakage(
      PoolFixture fixture) throws Exception {
    pool = lifecycledResizable(fixture);

    Runnable runner = createTaskClaimReleaseUntilShutdown(pool);

    Future<?> future = executor.submit(runner);

    long deadline = System.currentTimeMillis() + 1000;
    do {
      pool.claim(longTimeout).release();
    } while (System.currentTimeMillis() < deadline);
    pool.shutdown().await(longTimeout);
    future.get();
  }

  private Runnable createTaskClaimReleaseUntilShutdown(
      final LifecycledResizablePool<GenericPoolable> pool) {
    return new Runnable() {
      @Override
      public void run() {
        try {
          for (;;) {
            pool.claim(longTimeout).release();
          }
        } catch (InterruptedException e) {
          throw new RuntimeException(e);
        } catch (IllegalStateException e) {
          assertThat(e, hasMessage(equalTo("pool is shut down")));
        }
      }
    };
  }

  @Test(timeout = 16010)
  @Theory public void
  shutdownMustCompleteSuccessfullyEvenAtHighContention(
      PoolFixture fixture) throws Exception {
    int size = 100000;
    config.setSize(size);
    pool = lifecycledResizable(fixture);

    List<Future<?>> futures = new ArrayList<Future<?>>();
    for (int i = 0; i < 64; i++) {
      Runnable runner = createTaskClaimReleaseUntilShutdown(pool);
      futures.add(executor.submit(runner));
    }

    // Wait for all the objects to be created
    while (allocator.allocations() < size) {
      Thread.sleep(10);
    }

    // Very good, now shut down everything
    pool.shutdown().await(longTimeout);

    // Check that the shut down was orderly
    for (Future<?> future : futures) {
      future.get();
    }
  }

}
