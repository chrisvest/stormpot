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

import org.junit.After;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.experimental.theories.DataPoint;
import org.junit.experimental.theories.Theories;
import org.junit.experimental.theories.Theory;
import org.junit.rules.TestRule;
import org.junit.runner.RunWith;
import stormpot.*;

import java.util.*;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.hamcrest.Matchers.*;
import static org.junit.Assert.assertThat;
import static org.junit.Assume.assumeThat;
import static org.junit.internal.matchers.ThrowableMessageMatcher.hasMessage;
import static stormpot.AlloKit.*;

@SuppressWarnings("unchecked")
@RunWith(Theories.class)
public class PoolIT {
  @Rule public final TestRule failurePrinter = new FailurePrinterTestRule();
  @Rule public final ExecutorTestRule executorTestRule = new ExecutorTestRule();

  private static final Timeout longTimeout = new Timeout(1, TimeUnit.MINUTES);

  // Initialised by setUp()
  private CountingAllocator allocator;
  private Config<GenericPoolable> config;
  private ExecutorService executor;

  // Initialised in the tests
  private LifecycledResizablePool<GenericPoolable> pool;

  @DataPoint public static PoolFixture queuePool = new QueuePoolFixture();
  @DataPoint public static PoolFixture blazePool = new BlazePoolFixture();

  @Before public void
  setUp() {
    allocator = allocator();
    config = new Config<GenericPoolable>().setSize(1).setAllocator(allocator);
    executor = executorTestRule.getExecutorService();
  }

  @After public void
  verifyObjectsAreNeverDeallocatedMoreThanOnce() {
    List<GenericPoolable> deallocated = allocator.getDeallocations();
    Collections.sort(deallocated, new OrderByIdentityHashcode());
    Iterator<GenericPoolable> iter = deallocated.iterator();
    List<GenericPoolable> duplicates = new ArrayList<GenericPoolable>();
    if (iter.hasNext()) {
      GenericPoolable a = iter.next();
      while (iter.hasNext()) {
        GenericPoolable b = iter.next();
        if (a == b) {
          duplicates.add(b);
        }
        a = b;
      }
    }
    assertThat(duplicates, is(emptyIterableOf(GenericPoolable.class)));
  }

  private LifecycledResizablePool<GenericPoolable> lifecycledResizable(
      PoolFixture fixture) {
    Pool<GenericPoolable> pool = fixture.initPool(config);
    assumeThat(pool, instanceOf(LifecycledResizablePool.class));
    return (LifecycledResizablePool<GenericPoolable>) pool;
  }

  @Test(timeout = 16010)
  @Theory public void
  highContentionMustNotCausePoolLeakage(
      PoolFixture fixture) throws Exception {
    pool = lifecycledResizable(fixture);

    Runnable runner = createTaskClaimReleaseUntilShutdown(pool);

    Future<?> future = executor.submit(runner);
    executorTestRule.printOnFailure(future);

    long deadline = System.currentTimeMillis() + 5000;
    do {
      pool.claim(longTimeout).release();
    } while (System.currentTimeMillis() < deadline);
    pool.shutdown().await(longTimeout);
    future.get();
  }

  private Runnable createTaskClaimReleaseUntilShutdown(
      final LifecycledResizablePool<GenericPoolable> pool,
      final Class<? extends Throwable>... acceptableExceptions) {
    return new Runnable() {
      @Override
      public void run() {
        for (;;) {
          try {
            pool.claim(longTimeout).release();
          } catch (InterruptedException ignore) {
            // This is okay
          } catch (IllegalStateException e) {
            assertThat(e, hasMessage(equalTo("pool is shut down")));
            break;
          } catch (PoolException e) {
            assertThat(e.getCause().getClass(), isOneOf(acceptableExceptions));
          }
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
    executorTestRule.printOnFailure(futures);

    // Wait for all the objects to be created
    while (allocator.countAllocations() < size) {
      Thread.sleep(10);
    }

    // Very good, now shut down everything
    pool.shutdown().await(longTimeout);

    // Check that the shut down was orderly
    for (Future<?> future : futures) {
      future.get();
    }
  }

  @Test(timeout = 16010)
  @Theory public void
  highObjectChurnMustNotCausePoolLeakage(
      PoolFixture fixture) throws Exception {
    config.setSize(8);
    Action fallibleAction = new Action() {
      private final Random rnd = new Random();

      @Override
      public GenericPoolable apply(Slot slot, GenericPoolable obj) throws Exception {
        // About 20% of allocations, deallocations and reallocations will throw
        if (rnd.nextInt(1024) < 201) {
          throw new SomeRandomException();
        }
        return new GenericPoolable(slot);
      }
    };
    allocator = reallocator(
        alloc(fallibleAction),
        dealloc(fallibleAction),
        realloc(fallibleAction));
    config.setAllocator(allocator);
    config.setExpiration(new Expiration<GenericPoolable>() {
      @Override
      public boolean hasExpired(
          SlotInfo<? extends GenericPoolable> info) throws Exception {
        int x = info.randomInt();
        if ((x & 0xFF) > 250) {
          // About 3% of checks throw an exception
          throw new SomeRandomException();
        }
        // About 1 in 8 checks causes expiration
        return (x & 0x0F) < 0x02;
      }
    });

    pool = lifecycledResizable(fixture);

    List<Future<?>> futures = new ArrayList<Future<?>>();
    for (int i = 0; i < 64; i++) {
      Runnable runner = createTaskClaimReleaseUntilShutdown(
          pool,
          SomeRandomException.class);
      futures.add(executor.submit(runner));
    }
    executorTestRule.printOnFailure(futures);

    Thread.sleep(5000);

    // The shutdown completes if no objects are leaked
    pool.shutdown().await(longTimeout);

    for (Future<?> future : futures) {
      // Also verify that no unexpected exceptions were thrown
      future.get();
    }
  }
}
