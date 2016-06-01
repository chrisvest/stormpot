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
import org.junit.experimental.categories.Category;
import org.junit.rules.TestRule;
import org.junit.runner.RunWith;
import org.junit.runners.Parameterized;
import org.junit.runners.Parameterized.Parameters;
import stormpot.*;

import java.lang.management.ManagementFactory;
import java.lang.management.ThreadMXBean;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;

import static java.lang.System.identityHashCode;
import static org.hamcrest.Matchers.*;
import static org.junit.Assert.*;
import static org.junit.Assume.assumeTrue;
import static org.junit.internal.matchers.ThrowableMessageMatcher.hasMessage;
import static stormpot.AlloKit.$countDown;
import static stormpot.AlloKit.*;
import static stormpot.ExpireKit.*;

@SuppressWarnings("unchecked")
@Category(SlowTest.class)
@RunWith(Parameterized.class)
public class PoolIT {
  @Rule public final TestRule failurePrinter = new FailurePrinterTestRule();
  @Rule public final ExecutorTestRule executorTestRule = new ExecutorTestRule();

  private static final Timeout longTimeout = new Timeout(1, TimeUnit.MINUTES);
  private static final Timeout shortTimeout = new Timeout(1, TimeUnit.SECONDS);

  @Parameters(name = "{0}")
  public static Object[][] dataPoints() {
    return new Object[][] {
        {"blazePool", new BlazePoolFixture()},
        {"queuePool", new QueuePoolFixture()}
    };
  }

  private final PoolFixture fixture;

  // Initialised by setUp()
  private CountingAllocator allocator;
  private Config<GenericPoolable> config;
  private ExecutorService executor;

  // Initialised in the tests
  private Pool<GenericPoolable> pool;

  @SuppressWarnings("UnusedParameters")
  public PoolIT(String implementationName, PoolFixture fixture) {
    this.fixture = fixture;
  }

  @Before public void
  setUp() {
    allocator = allocator();
    config = new Config<GenericPoolable>().setSize(1).setAllocator(allocator);
    executor = executorTestRule.getExecutorService();
  }

  @After public void
  verifyObjectsAreNeverDeallocatedMoreThanOnce() throws InterruptedException {
    assertTrue("pool should have been shut down by the test",
        pool.shutdown().await(shortTimeout));
    pool = null;

    List<GenericPoolable> deallocated = allocator.getDeallocations();
    // Synchronize to avoid ConcurrentModification with background thread
    synchronized (deallocated) {
      Collections.sort(deallocated, (a,b) ->
          Integer.compare(identityHashCode(a), identityHashCode(b)));
      Iterator<GenericPoolable> iter = deallocated.iterator();
      List<GenericPoolable> duplicates = new ArrayList<>();
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
    allocator = null;
  }

  private void createPool() {
    pool = fixture.initPool(config);
  }

  @Test(timeout = 16010) public void
  highContentionMustNotCausePoolLeakage() throws Exception {
    createPool();

    Runnable runner = createTaskClaimReleaseUntilShutdown(pool);

    Future<?> future = executor.submit(runner);
    executorTestRule.printOnFailure(future);

    long deadline = System.currentTimeMillis() + 5000;
    do {
      pool.claim(longTimeout).release();
    } while (System.currentTimeMillis() < deadline);
    assertTrue(pool.shutdown().await(longTimeout));
    future.get();
  }

  private Runnable createTaskClaimReleaseUntilShutdown(
      final Pool<GenericPoolable> pool,
      final Class<? extends Throwable>... acceptableExceptions) {
    return () -> {
      for (;;) {
        try {
          pool.claim(longTimeout).release();
        } catch (InterruptedException ignore) {
          // This is okay
        } catch (IllegalStateException e) {
          assertThat(e, hasMessage(equalTo("Pool has been shut down")));
          break;
        } catch (PoolException e) {
          assertThat(e.getCause().getClass(), isOneOf(acceptableExceptions));
        }
      }
    };
  }

  @Test(timeout = 16010) public void
  shutdownMustCompleteSuccessfullyEvenAtHighContention() throws Exception {
    int size = 100000;
    config.setSize(size);
    createPool();

    List<Future<?>> futures = new ArrayList<>();
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
    assertTrue(pool.shutdown().await(longTimeout));

    // Check that the shut down was orderly
    for (Future<?> future : futures) {
      future.get();
    }
  }

  @Test(timeout = 16010) public void
  highObjectChurnMustNotCausePoolLeakage() throws Exception {
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
    config.setExpiration(info -> {
      int x = info.randomInt();
      if ((x & 0xFF) > 250) {
        // About 3% of checks throw an exception
        throw new SomeRandomException();
      }
      // About 1 in 8 checks causes expiration
      return (x & 0x0F) < 0x02;
    });

    createPool();

    List<Future<?>> futures = new ArrayList<>();
    for (int i = 0; i < 64; i++) {
      Runnable runner = createTaskClaimReleaseUntilShutdown(
          pool,
          SomeRandomException.class);
      futures.add(executor.submit(runner));
    }
    executorTestRule.printOnFailure(futures);

    Thread.sleep(5000);

    // The shutdown completes if no objects are leaked
    assertTrue(pool.shutdown().await(longTimeout));

    for (Future<?> future : futures) {
      // Also verify that no unexpected exceptions were thrown
      future.get();
    }
  }

  @Test(timeout = 16010) public void
  backgroundExpirationMustDoNothingWhenPoolIsDepleted() throws Exception {
    AtomicBoolean hasExpired = new AtomicBoolean();
    CountingExpiration expiration = expire($expiredIf(hasExpired));
    config.setExpiration(expiration);
    config.setBackgroundExpirationEnabled(true);

    createPool();

    // Do a thread-local reclaim, if applicable, to keep the object in
    // circulation
    pool.claim(longTimeout).release();
    GenericPoolable obj = pool.claim(longTimeout);
    int expirationsCount = expiration.countExpirations();

    hasExpired.set(true);

    Thread.sleep(1000);

    assertThat(allocator.countDeallocations(), is(0));
    assertThat(expiration.countExpirations(), is(expirationsCount));
    obj.release();
  }

  @Test(timeout = 16010) public void
  backgroundExpirationMustNotFailWhenThereAreNoObjectsInCirculation()
      throws Exception {
    AtomicBoolean hasExpired = new AtomicBoolean();
    CountingExpiration expiration = expire($expiredIf(hasExpired));
    config.setExpiration(expiration);
    config.setBackgroundExpirationEnabled(true);

    createPool();

    GenericPoolable obj = pool.claim(longTimeout);
    int expirationsCount = expiration.countExpirations();

    hasExpired.set(true);

    Thread.sleep(1000);

    assertThat(allocator.countDeallocations(), is(0));
    assertThat(expiration.countExpirations(), is(expirationsCount));
    obj.release();
  }

  @Test(timeout = 160100) public void
  decreasingSizeOfDepletedPoolMustOnlyDeallocateAllocatedObjects()
      throws Exception {
    int startingSize = 256;
    CountDownLatch startLatch = new CountDownLatch(startingSize);
    Semaphore semaphore = new Semaphore(0);
    allocator = allocator(
        alloc($countDown(startLatch, $new)),
        dealloc($release(semaphore, $null)));
    config.setSize(startingSize);
    config.setAllocator(allocator);
    createPool();
    startLatch.await();
    List<GenericPoolable> objs = new ArrayList<>();
    for (int i = 0; i < startingSize; i++) {
      objs.add(pool.claim(longTimeout));
    }

    int size = startingSize;
    List<GenericPoolable> subList = objs.subList(0, startingSize - 1);
    for (GenericPoolable obj : subList) {
      size--;
      pool.setTargetSize(size);
      // It's important that the wait mask produces values greater than the
      // allocation threads idle wait time.
      assertFalse(semaphore.tryAcquire(size & 127, TimeUnit.MILLISECONDS));
      obj.release();
      semaphore.acquire();
    }

    assertThat(allocator.getDeallocations(), equalTo(subList));

    objs.get(startingSize - 1).release();
  }

  @Test(timeout = 160100) public void
  mustNotDeallocateNullsFromLiveQueueDuringShutdown() throws Exception {
    int startingSize = 256;
    CountDownLatch startLatch = new CountDownLatch(startingSize);
    Semaphore semaphore = new Semaphore(0);
    allocator = allocator(
        alloc($countDown(startLatch, $new)),
        dealloc($release(semaphore, $null)));
    config.setSize(startingSize);
    config.setAllocator(allocator);
    createPool();
    startLatch.await();
    List<GenericPoolable> objs = new ArrayList<>();
    for (int i = 0; i < startingSize; i++) {
      objs.add(pool.claim(longTimeout));
    }

    Completion completion = pool.shutdown();
    int size = startingSize;
    List<GenericPoolable> subList = objs.subList(0, startingSize - 1);
    for (GenericPoolable obj : subList) {
      size--;
      // It's important that the wait mask produces values greater than the
      // allocation threads idle wait time.
      assertFalse(semaphore.tryAcquire(size & 127, TimeUnit.MILLISECONDS));
      obj.release();
      semaphore.acquire();
    }

    assertThat(allocator.getDeallocations(), equalTo(subList));

    objs.get(startingSize - 1).release();
    assertTrue("shutdown timeout elapsed", completion.await(longTimeout));
  }

  @Test public void
  explicitlyExpiredSlotsMustNotCauseBackgroundCPUBurn()
      throws InterruptedException {
    final ThreadMXBean threads = ManagementFactory.getThreadMXBean();
    final AtomicLong lastUserTimeIncrement = new AtomicLong();
    assumeTrue(threads.isCurrentThreadCpuTimeSupported());
    allocator = allocator(alloc(
        measureLastCPUTime(threads, lastUserTimeIncrement)));
    config.setAllocator(allocator);
    config.setSize(2);
    createPool();
    GenericPoolable a = pool.claim(longTimeout);
    GenericPoolable b = pool.claim(longTimeout);
    a.expire();
    Thread.sleep(10);
    a.release();
    a = pool.claim(longTimeout);
    long millisecondsAllowedToBurnCPU = 5000;
    Thread.sleep(millisecondsAllowedToBurnCPU);
    b.expire();
    b.release();
    b = pool.claim(longTimeout);
    a.release();
    b.release();
    long millisecondsSpentBurningCPU =
        TimeUnit.NANOSECONDS.toMillis(lastUserTimeIncrement.get());

    assertThat(millisecondsSpentBurningCPU,
        is(lessThan(millisecondsAllowedToBurnCPU / 2)));
  }

  private Action measureLastCPUTime(final ThreadMXBean threads, final AtomicLong lastUserTimeIncrement) {
    return new Action() {
      boolean first = true;

      @Override
      public GenericPoolable apply(Slot slot, GenericPoolable obj) throws Exception {
        if (first) {
          threads.setThreadCpuTimeEnabled(true);
          first = false;
        }
        long userTime = threads.getCurrentThreadUserTime();
        lastUserTimeIncrement.set(userTime - lastUserTimeIncrement.get());
        return $new.apply(slot, obj);
      }
    };
  }

  @Test public void
  explicitlyExpiredSlotsThatAreDeallocatedThroughPoolShrinkingMustNotCauseBackgroundCPUBurn()
      throws InterruptedException {
    final ThreadMXBean threads = ManagementFactory.getThreadMXBean();
    final AtomicLong lastUserTimeIncrement = new AtomicLong();
    final AtomicLong maxUserTimeIncrement = new AtomicLong();
    assumeTrue(threads.isCurrentThreadCpuTimeSupported());
    allocator = allocator(alloc(new Action() {
      boolean first = true;

      @Override
      public GenericPoolable apply(Slot slot, GenericPoolable obj) throws Exception {
        if (first) {
          threads.setThreadCpuTimeEnabled(true);
          first = false;
        }
        long userTime = threads.getCurrentThreadUserTime();
        long delta = userTime - lastUserTimeIncrement.get();
        lastUserTimeIncrement.set(delta);
        long existingDelta;
        do {
          existingDelta = maxUserTimeIncrement.get();
        } while ( !maxUserTimeIncrement.compareAndSet(
            existingDelta, Math.max(delta, existingDelta)));
        return $new.apply(slot, obj);
      }
    }));
    config.setAllocator(allocator);
    int size = 30;
    config.setSize(size);
    createPool();
    LinkedList<GenericPoolable> objs = new LinkedList<>();
    for (int i = 0; i < size; i++) {
      GenericPoolable obj = pool.claim(longTimeout);
      objs.offer(obj);
      obj.expire();
    }
    int newSize = size / 3;
    pool.setTargetSize(newSize);
    Iterator<GenericPoolable> itr = objs.iterator();
    for (int i = size; i >= newSize; i--) {
      itr.next().release();
      itr.remove();
    }
    long millisecondsAllowedToBurnCPU = 5000;
    Thread.sleep(millisecondsAllowedToBurnCPU);
    while (itr.hasNext()) {
      itr.next().release();
    }
    pool.claim(longTimeout).release();

    long millisecondsSpentBurningCPU =
        TimeUnit.NANOSECONDS.toMillis(maxUserTimeIncrement.get());

    assertThat(millisecondsSpentBurningCPU,
        is(lessThan(millisecondsAllowedToBurnCPU / 2)));
  }

  @Test public void
  explicitlyExpiredButUnreleasedSlotsMustNotCauseBackgroundCPUBurn()
      throws InterruptedException {
    final ThreadMXBean threads = ManagementFactory.getThreadMXBean();
    final AtomicLong lastUserTimeIncrement = new AtomicLong();
    assumeTrue(threads.isCurrentThreadCpuTimeSupported());
    allocator = allocator(alloc(
        measureLastCPUTime(threads, lastUserTimeIncrement)));
    config.setAllocator(allocator);
    createPool();
    GenericPoolable a = pool.claim(longTimeout);
    a.expire();

    long millisecondsAllowedToBurnCPU = 5000;
    Thread.sleep(millisecondsAllowedToBurnCPU);

    a.release();
    pool.claim(longTimeout).release();

    long millisecondsSpentBurningCPU =
        TimeUnit.NANOSECONDS.toMillis(lastUserTimeIncrement.get());

    assertThat(millisecondsSpentBurningCPU,
        is(lessThan(millisecondsAllowedToBurnCPU / 2)));
  }

  @Test public void
  mustNotBurnTooMuchCPUWhileThePoolIsWorkingOnShrinking()
      throws InterruptedException {
    final ThreadMXBean threads = ManagementFactory.getThreadMXBean();
    final AtomicLong lastUserTimeIncrement = new AtomicLong();
    int size = 20;
    assumeTrue(threads.isCurrentThreadCpuTimeSupported());
    allocator = allocator(alloc(
        measureLastCPUTime(threads, lastUserTimeIncrement)));
    config.setAllocator(allocator);
    config.setSize(size);
    createPool();
    LinkedList<GenericPoolable> objs = new LinkedList<>();
    for (int i = 0; i < size; i++) {
      objs.add(pool.claim(longTimeout));
    }
    pool.setTargetSize(1);

    long millisecondsAllowedToBurnCPU = 5000;
    Thread.sleep(millisecondsAllowedToBurnCPU);

    for (GenericPoolable obj : objs) {
      obj.expire();
      obj.release();
    }
    pool.claim(longTimeout).release();

    long millisecondsSpentBurningCPU =
        TimeUnit.NANOSECONDS.toMillis(lastUserTimeIncrement.get());

    assertThat(millisecondsSpentBurningCPU,
        is(lessThan(millisecondsAllowedToBurnCPU / 2)));
  }
}
