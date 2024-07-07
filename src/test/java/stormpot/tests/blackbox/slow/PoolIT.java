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
package stormpot.tests.blackbox.slow;

import stormpot.tests.extensions.ExecutorExtension;
import stormpot.tests.extensions.FailurePrinterExtension;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInfo;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.extension.RegisterExtension;
import stormpot.Completion;
import testkits.GenericPoolable;
import stormpot.Pool;
import stormpot.PoolBuilder;
import stormpot.PoolException;
import stormpot.Slot;
import testkits.SomeRandomException;
import stormpot.Timeout;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.Iterator;
import java.util.List;
import java.util.Random;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Future;
import java.util.concurrent.Semaphore;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.util.Arrays.asList;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static testkits.AlloKit.$countDown;
import static testkits.AlloKit.$new;
import static testkits.AlloKit.$null;
import static testkits.AlloKit.$release;
import static testkits.AlloKit.Action;
import static testkits.AlloKit.CountingAllocator;
import static testkits.AlloKit.alloc;
import static testkits.AlloKit.allocator;
import static testkits.AlloKit.dealloc;
import static testkits.AlloKit.realloc;
import static testkits.AlloKit.reallocator;

@SuppressWarnings("unchecked")
@ExtendWith(FailurePrinterExtension.class)
abstract class PoolIT {
  @RegisterExtension
  final ExecutorExtension executorExtension = new ExecutorExtension();

  protected static final Timeout longTimeout = new Timeout(5, TimeUnit.MINUTES);
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
  void verifyObjectsAreNeverDeallocatedMoreThanOnce(TestInfo info) throws InterruptedException {
    assertTrue(pool.shutdown().await(shortTimeout),
            "pool should have been shut down by the test: " + info.getDisplayName());
    pool = null;

    List<GenericPoolable> deallocated = allocator.getDeallocations();
    // Synchronize to avoid ConcurrentModification with background thread
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
          GenericPoolable obj = pool.claim(longTimeout);
          obj.release();
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
      //noinspection BusyWait
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

    List<GenericPoolable> deallocations = allocator.getDeallocations();
    synchronized (deallocations) {
      assertThat(deallocations).isEqualTo(subList);
    }

    objs.get(startingSize - 1).release();
    assertTrue(completionFuture.get(1, TimeUnit.MINUTES), "shutdown timeout elapsed");
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
      long start = System.nanoTime();
      Thread.sleep(100);
      long elapsedMillis = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - start);
      if (elapsedMillis >= 150) {
        // Ignore outliers with very high sleep time.
        i--;
        continue;
      }
      curr = counter.get();
      long delta = curr - prev;
      prev = curr;
      if (i > 40) {
        assertThat(delta).isLessThanOrEqualTo(5);
      }
    }
  }
}
