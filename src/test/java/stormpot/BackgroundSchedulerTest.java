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
package stormpot;

import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;

import java.lang.Thread.State;
import java.util.Queue;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.locks.LockSupport;

import static java.util.concurrent.TimeUnit.MILLISECONDS;
import static org.hamcrest.Matchers.*;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertThat;
import static stormpot.UnitKit.spinwait;
import static stormpot.UnitKit.waitForThreadState;

public class BackgroundSchedulerTest {
  private static final long TIMEOUT = 5000;

  private Queue<Thread> createdThreads;
  private ThreadFactory threadFactory;
  private int maxThreads = 5;
  private BackgroundScheduler backgroundScheduler;

  @Rule
  public final FailurePrinterTestRule failurePrinterTestRule =
      new FailurePrinterTestRule();

  @Before
  public void setUp() {
    createdThreads = new ConcurrentLinkedQueue<>();
    threadFactory = r -> {
      Thread thread = StormpotThreadFactory.INSTANCE.newThread(r);
      createdThreads.add(thread);
      return thread;
    };
    Thread.interrupted(); // Clear stray interrupts
  }

  private void createBackgroundScheduler() {
    backgroundScheduler = new BackgroundScheduler(threadFactory, maxThreads);
  }

  private void verifyProgressionOfTime(BackgroundScheduler backgroundScheduler)
      throws Exception {
    MonotonicTimeSource timeSource =
        backgroundScheduler.getAsynchronousMonotonicTimeSource();

    long a = System.nanoTime();
    Thread.sleep(30);
    long b = timeSource.nanoTime();
    long c = timeSource.nanoTime();
    long d = System.nanoTime();

    assertThat(a, lessThan(b));
    assertThat(b, lessThanOrEqualTo(c));
    assertThat(c, lessThanOrEqualTo(d));
  }

  @Test(expected = IllegalArgumentException.class) public void
  mustThrowIfGivenThreadFactoryIsNull() throws Exception {
    threadFactory = null;
    createBackgroundScheduler();
  }

  @Test(expected = IllegalArgumentException.class) public void
  mustThrowIfMaxAllocationThreadsAreNotPositive() throws Exception {
    maxThreads = 0;
    createBackgroundScheduler();
  }

  @Test public void
  mustAcceptMaxAllocationThreadsOfOne() throws Exception {
    maxThreads = 1;
    createBackgroundScheduler();
  }

  @Test(timeout = TIMEOUT) public void
  mustNotRunAnyThreadsWhenReferenceCountIsZero() throws Exception {
    int firstCount = Thread.activeCount();
    createBackgroundScheduler();
    int secondCount = Thread.activeCount();
    assertThat(secondCount, is(firstCount));
  }

  @Test(timeout = TIMEOUT) public void
  mustNotRunAnyThreadsAfterReferenceCountReachesZero()
      throws Exception {
    int firstCount = Thread.activeCount();
    createBackgroundScheduler();
    backgroundScheduler.incrementReferences();
    backgroundScheduler.decrementReferences();
    int secondCount = Thread.activeCount();
    assertThat(secondCount, is(firstCount));
  }

  @Test(timeout = TIMEOUT) public void
  mustProvideTimeWhenReferenceCountIsZero() throws Exception {
    createBackgroundScheduler();
    verifyProgressionOfTime(backgroundScheduler);
  }

  @Test(timeout = TIMEOUT) public void
  mustProvideTimeWhenReferenceCountIsNonZero() throws Exception {
    createBackgroundScheduler();
    backgroundScheduler.incrementReferences();

    try {
      verifyProgressionOfTime(backgroundScheduler);
    } finally {
      backgroundScheduler.decrementReferences();
    }
  }

  @Test(timeout = TIMEOUT) public void
  mustProvideTimeWhenReferenceCountReturnsToZero()
      throws Exception {
    createBackgroundScheduler();
    backgroundScheduler.incrementReferences();
    Thread.sleep(30);
    backgroundScheduler.decrementReferences();

    verifyProgressionOfTime(backgroundScheduler);
  }

  @Test(timeout = TIMEOUT) public void
  mustCreateThreadsWithProvidedThreadFactory() throws Exception {
    createBackgroundScheduler();
    backgroundScheduler.incrementReferences();
    try {
      Thread thread = createdThreads.poll();
      assertThat(thread, is(not(nullValue())));
      assertThat(thread.getState(), isOneOf(
          State.RUNNABLE, State.TIMED_WAITING));
    } finally {
      backgroundScheduler.decrementReferences();
    }
  }

  @Test(timeout = TIMEOUT) public void
  mustStopAllCreatedThreadsWhenReferenceCountGoesToZero()
      throws Exception {
    createBackgroundScheduler();
    backgroundScheduler.incrementReferences();
    waitForThreadState(createdThreads.peek(), State.RUNNABLE);
    backgroundScheduler.decrementReferences();

    assertThat(createdThreads.size(), is(greaterThan(0)));
    createdThreads.forEach(th ->
        assertThat(th.getState(), is(State.TERMINATED)));
  }

  @Test(timeout = TIMEOUT) public void
  mustStopAllCreatedThreadsWhenReferenceCountGoesToZeroEvenWhenInterrupted()
      throws Exception {
    createBackgroundScheduler();
    backgroundScheduler.incrementReferences();
    waitForThreadState(createdThreads.peek(), State.RUNNABLE);
    Thread.currentThread().interrupt();
    backgroundScheduler.decrementReferences();

    assertThat(createdThreads.size(), is(greaterThan(0)));
    createdThreads.forEach(th ->
        assertThat(th.getState(), is(State.TERMINATED)));
  }

  @Test(timeout = TIMEOUT) public void
  timerThreadMustNotStopEvenIfSpuriouslyInterrupted() throws Exception {
    createBackgroundScheduler();
    backgroundScheduler.incrementReferences();
    Thread timer = createdThreads.poll();
    waitForThreadState(timer, State.RUNNABLE);
    timer.interrupt();
    try {
      timer.join(20);
      State state = timer.getState();
      assertThat(state, isOneOf(State.RUNNABLE, State.TIMED_WAITING));
    } finally {
      backgroundScheduler.decrementReferences();
    }
  }

  @Test(timeout = TIMEOUT) public void
  submittedTasksMustExecuteInThreadsFromTheGivenThreadFactory()
      throws Exception {
    BlockingQueue<Thread> queue = new LinkedBlockingQueue<>();
    createBackgroundScheduler();
    backgroundScheduler.incrementReferences();
    try {
      backgroundScheduler.submit(() -> queue.offer(Thread.currentThread()));
      Thread taskThread = queue.take();
      assertThat(taskThread, isIn(createdThreads));
    } finally {
      backgroundScheduler.decrementReferences();
    }
  }

  @Test(timeout = TIMEOUT) public void
  backgroundTaskExecutionMustWorkAfterRestart() throws Exception {
    createBackgroundScheduler();
    backgroundScheduler.incrementReferences();
    Semaphore semaphore = new Semaphore(0);
    backgroundScheduler.submit(semaphore::release);
    semaphore.acquire();
    backgroundScheduler.decrementReferences();

    backgroundScheduler.incrementReferences();
    backgroundScheduler.submit(semaphore::release);
    // Assert that we don't time out on the acquire here:
    semaphore.acquire();
    backgroundScheduler.decrementReferences();
  }

  @Test(timeout = TIMEOUT) public void
  mustNotCreateMoreAllocationThreadsThanNeeded() throws Exception {
    createBackgroundScheduler();
    backgroundScheduler.incrementReferences();
    Semaphore semaphore = new Semaphore(0);
    try {
      int sleepTime = 10;
      int iterations = 10;
      for (int i = 0; i < iterations; i++) {
        backgroundScheduler.submit(semaphore::release);
        semaphore.acquire();
        Thread.sleep(sleepTime);
      }
      assertThat(createdThreads.size(), lessThan(iterations));
    } finally {
      backgroundScheduler.decrementReferences();
    }
  }

  @Test(timeout = TIMEOUT) public void
  mustScaleAllocationThreadsUpAsNeeded() throws Exception {
    // Issuing 10 allocations that each take 100 milliseconds to complete,
    // we should find that more than 5 threads are created... presumably
    createBackgroundScheduler();
    backgroundScheduler.incrementReferences();
    int threadCount = 10;
    long parkTimeNanos = MILLISECONDS.toNanos(100);
    Semaphore semaphore = new Semaphore(0);
    Runnable sleeper = () -> LockSupport.parkNanos(parkTimeNanos);
    for (int i = 0; i < threadCount; i++) {
      backgroundScheduler.submit(sleeper);
    }
    backgroundScheduler.submit(semaphore::release);
    semaphore.acquire();
    int size = createdThreads.size();
    backgroundScheduler.decrementReferences();
    assertThat(size, greaterThan(3));
  }

  @Test(timeout = TIMEOUT) public void
  mustRunRecurringTaskUntilCancelled() throws Exception {
    createBackgroundScheduler();
    backgroundScheduler.incrementReferences();
    Semaphore semaphore = new Semaphore(0);
    long start = System.nanoTime();
    ScheduledJobTask task = backgroundScheduler.scheduleWithFixedDelay(
        semaphore::release, 1, MILLISECONDS);
    semaphore.acquire(5);
    task.stop();
    long elapsedNanos = System.nanoTime() - start;
    int elapsedMillis = (int) TimeUnit.NANOSECONDS.toMillis(elapsedNanos);
    spinwait(10);
    backgroundScheduler.decrementReferences();
    // The task has a tight schedule, so the cancel signal might come a few
    // runs late. So if we try to grab a few MORE permits than what should have
    // become available, then we should fail.
    assertFalse(semaphore.tryAcquire((elapsedMillis - 10) + 3));
  }

  @Test(timeout = TIMEOUT) public void
  mustRunRecurringTaskEvenIfItThrows() throws Exception {
    createBackgroundScheduler();
    backgroundScheduler.incrementReferences();
    Semaphore semaphore = new Semaphore(0);
    ScheduledJobTask task = backgroundScheduler.scheduleWithFixedDelay(() -> {
      semaphore.release();
      throw new RuntimeException("boo");
    }, 1, MILLISECONDS);
    semaphore.acquire(2); // assert this doesn't time out
    task.stop();
    backgroundScheduler.decrementReferences();
  }

  @Test(timeout = TIMEOUT) public void
  scheduledTasksMustStopWhenReferenceCountReachesZero() throws Exception {
    createBackgroundScheduler();
    backgroundScheduler.incrementReferences();
    AtomicInteger counter = new AtomicInteger();
    backgroundScheduler.scheduleWithFixedDelay(
        counter::incrementAndGet, 1, MILLISECONDS);
    //noinspection StatementWithEmptyBody
    while (counter.get() < 5);
    backgroundScheduler.decrementReferences();
    int a = counter.get();
    spinwait(5);
    int b = counter.get();
    assertThat(a, is(b));
  }

  @Test(timeout = TIMEOUT, expected = IllegalStateException.class) public void
  submittingScheduledTaskMustThrowIfBackgroundSchedulerHasBeenStopped()
      throws InterruptedException {
    createBackgroundScheduler();
    backgroundScheduler.incrementReferences();
    Semaphore semaphore = new Semaphore(0);
    backgroundScheduler.scheduleWithFixedDelay(
        semaphore::release, 1, MILLISECONDS);
    semaphore.acquire();
    backgroundScheduler.decrementReferences();
    backgroundScheduler.scheduleWithFixedDelay(() -> {}, 1, MILLISECONDS);
  }

  @Test public void
  defaultBackgroundSchedulerMustHaveDefaultThreadFactoryAndThreadCount() {
    BackgroundScheduler scheduler = BackgroundScheduler.getDefaultInstance();
    int availableProcessors = Runtime.getRuntime().availableProcessors();

    assertThat(scheduler.getThreadFactory(),
        sameInstance(StormpotThreadFactory.INSTANCE));
    assertThat(scheduler.getMaxThreads(), is(availableProcessors));
  }

  @Test public void
  defaultBackgroundSchedulerInstanceMustBeSettable() {
    BackgroundScheduler oldDefault = BackgroundScheduler.getDefaultInstance();
    try {
      BackgroundScheduler scheduler = new BackgroundScheduler(r -> null, 4);
      BackgroundScheduler.setDefaultInstance(scheduler);
      assertThat(BackgroundScheduler.getDefaultInstance(),
          sameInstance(scheduler));
    } finally {
      BackgroundScheduler.setDefaultInstance(oldDefault);
    }
  }

  @Test(expected = IllegalArgumentException.class) public void
  defaultBackgroundSchedulerInstanceCannotBeSetToNull() {
    BackgroundScheduler.setDefaultInstance(null);
  }
}
