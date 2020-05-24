/*
 * Copyright © 2011-2019 Chris Vest (mr.chrisvest@gmail.com)
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
package blackbox.slow;

import extensions.ExecutorExtension;
import extensions.FailurePrinterExtension;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.extension.RegisterExtension;
import stormpot.*;

import java.lang.management.ManagementFactory;
import java.lang.management.ThreadMXBean;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicLong;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.util.Arrays.asList;
import static org.junit.jupiter.api.Assertions.*;
import static org.junit.jupiter.api.Assumptions.assumeTrue;
import static stormpot.AlloKit.*;

@SuppressWarnings("unchecked")
@ExtendWith(FailurePrinterExtension.class)
abstract class PoolIT {
  @RegisterExtension
  final ExecutorExtension executorExtension = new ExecutorExtension();

  protected static final Timeout longTimeout = new Timeout(1, TimeUnit.MINUTES);
  protected static final Timeout shortTimeout = new Timeout(1, TimeUnit.SECONDS);

  // Initialised by setUp()
  protected CountingAllocator allocator;
  protected PoolBuilder<GenericPoolable> builder;
  protected ExecutorService executor;

  // Initialised in the tests
  protected Pool<GenericPoolable> pool;

  @BeforeEach
  void setUp() {
    allocator = allocator();
    builder = createPoolBuilder(allocator).setSize(1);
    executor = executorExtension.getExecutorService();
  }

  protected abstract PoolBuilder<GenericPoolable> createPoolBuilder(CountingAllocator allocator);

  @AfterEach
  void verifyObjectsAreNeverDeallocatedMoreThanOnce() throws InterruptedException {
    assertTrue(pool.shutdown().await(shortTimeout), "pool should have been shut down by the test");
    pool = null;

    List<GenericPoolable> deallocated = allocator.getDeallocations();
    // Synchronize to avoid ConcurrentModification with background thread
    //noinspection SynchronizationOnLocalVariableOrMethodParameter
    synchronized (deallocated) {
      deallocated.sort(Comparator.comparingInt(System::identityHashCode));
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
      assertThat(duplicates).isEmpty();
    }
    allocator = null;
  }

  protected void createPool() {
    pool = builder.build();
  }

  @org.junit.jupiter.api.Timeout(160)
  @Test
  void highContentionMustNotCausePoolLeakage() throws Exception {
    createPool();

    Runnable runner = createTaskClaimReleaseUntilShutdown(pool);

    Future<?> future = executor.submit(runner);
    executorExtension.printOnFailure(future);

    long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
    do {
      pool.claim(longTimeout).release();
    } while (System.nanoTime() < deadline);
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
          assertThat(e).hasMessage("Pool has been shut down");
          break;
        } catch (PoolException e) {
          assertThat(e.getCause().getClass()).isIn(asList(acceptableExceptions));
        }
      }
    };
  }

  @org.junit.jupiter.api.Timeout(160)
  @Test
  void shutdownMustCompleteSuccessfullyEvenAtHighContention() throws Exception {
    int size = 100000;
    builder.setSize(size);
    createPool();

    List<Future<?>> futures = new ArrayList<>();
    for (int i = 0; i < 64; i++) {
      Runnable runner = createTaskClaimReleaseUntilShutdown(pool);
      futures.add(executor.submit(runner));
    }
    executorExtension.printOnFailure(futures);

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

  @org.junit.jupiter.api.Timeout(160)
  @Test
  void highObjectChurnMustNotCausePoolLeakage() throws Exception {
    builder.setSize(8);
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
    builder.setAllocator(allocator);
    builder.setExpiration(info -> {
      int x = ThreadLocalRandom.current().nextInt();
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
    executorExtension.printOnFailure(futures);

    Thread.sleep(5000);

    // The shutdown completes if no objects are leaked
    assertTrue(pool.shutdown().await(longTimeout));

    for (Future<?> future : futures) {
      // Also verify that no unexpected exceptions were thrown
      future.get();
    }
  }

  @org.junit.jupiter.api.Timeout(160)
  @Test
  void mustNotDeallocateNullsFromLiveQueueDuringShutdown() throws Exception {
    int startingSize = 256;
    CountDownLatch startLatch = new CountDownLatch(startingSize);
    Semaphore semaphore = new Semaphore(0);
    allocator = allocator(
        alloc($countDown(startLatch, $new)),
        dealloc($release(semaphore, $null)));
    builder.setSize(startingSize);
    builder.setAllocator(allocator);
    createPool();
    startLatch.await();
    List<GenericPoolable> objs = new ArrayList<>();
    for (int i = 0; i < startingSize; i++) {
      objs.add(pool.claim(longTimeout));
    }

    Completion completion = pool.shutdown();
    Future<Boolean> completionFuture = executor.submit(() -> completion.await(longTimeout));
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

    assertThat(allocator.getDeallocations()).isEqualTo(subList);

    objs.get(startingSize - 1).release();
    assertTrue(completionFuture.get(1, TimeUnit.MINUTES), "shutdown timeout elapsed");
  }

  @Test
  void explicitlyExpiredSlotsMustNotCauseBackgroundCPUBurn()
      throws InterruptedException {
    final ThreadMXBean threads = ManagementFactory.getThreadMXBean();
    final AtomicLong lastUserTimeIncrement = new AtomicLong();
    assumeTrue(threads.isCurrentThreadCpuTimeSupported());
    allocator = allocator(alloc(
        measureLastCPUTime(threads, lastUserTimeIncrement)));
    builder.setAllocator(allocator);
    builder.setSize(2);
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

    assertThat(millisecondsSpentBurningCPU).isLessThan(millisecondsAllowedToBurnCPU / 2);
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

  @Test
  void explicitlyExpiredSlotsThatAreDeallocatedThroughPoolShrinkingMustNotCauseBackgroundCPUBurn()
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
    builder.setAllocator(allocator);
    int size = 30;
    builder.setSize(size);
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

    assertThat(millisecondsSpentBurningCPU).isLessThan(millisecondsAllowedToBurnCPU / 2);
  }

  @Test
  void explicitlyExpiredButUnreleasedSlotsMustNotCauseBackgroundCPUBurn()
      throws InterruptedException {
    final ThreadMXBean threads = ManagementFactory.getThreadMXBean();
    final AtomicLong lastUserTimeIncrement = new AtomicLong();
    assumeTrue(threads.isCurrentThreadCpuTimeSupported());
    allocator = allocator(alloc(
        measureLastCPUTime(threads, lastUserTimeIncrement)));
    builder.setAllocator(allocator);
    createPool();
    GenericPoolable a = pool.claim(longTimeout);
    a.expire();

    long millisecondsAllowedToBurnCPU = 5000;
    Thread.sleep(millisecondsAllowedToBurnCPU);

    a.release();
    pool.claim(longTimeout).release();

    long millisecondsSpentBurningCPU =
        TimeUnit.NANOSECONDS.toMillis(lastUserTimeIncrement.get());

    assertThat(millisecondsSpentBurningCPU).isLessThan(millisecondsAllowedToBurnCPU / 2);
  }

  @Test
  void mustNotBurnTooMuchCPUWhileThePoolIsWorkingOnShrinking()
      throws InterruptedException {
    final ThreadMXBean threads = ManagementFactory.getThreadMXBean();
    final AtomicLong lastUserTimeIncrement = new AtomicLong();
    int size = 20;
    assumeTrue(threads.isCurrentThreadCpuTimeSupported());
    allocator = allocator(alloc(
        measureLastCPUTime(threads, lastUserTimeIncrement)));
    builder.setAllocator(allocator);
    builder.setSize(size);
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

    assertThat(millisecondsSpentBurningCPU).isLessThan(millisecondsAllowedToBurnCPU / 2);
  }

  @Test
  void mustGraduallyReduceAggressivenessInRepairingFailingAllocator() throws Exception {
    AtomicLong counter = new AtomicLong();
    allocator = allocator(alloc($new, (slot, obj) -> {
      counter.getAndIncrement();
      throw new RuntimeException("boom");
    }));
    builder.setAllocator(allocator);
    createPool();
    GenericPoolable obj = pool.claim(longTimeout);
    obj.expire();
    obj.release();
    assertThrows(PoolException.class, () -> pool.claim(longTimeout).release());
    long prev = 0, curr;
    for (int i = 0; i < 50; i++) {
      Thread.sleep(100);
      curr = counter.get();
      long delta = curr - prev;
      prev = curr;
      if (i > 40) {
        assertThat(delta).isLessThanOrEqualTo(5);
      }
    }
  }
}
