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

public class BackgroundProcessTest {
  private static final long TIMEOUT = 5000;

  private Queue<Thread> createdThreads;
  private ThreadFactory threadFactory;
  private int maxThreads = 5;
  private BackgroundProcess backgroundProcess;

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

  private void createBackgroundProcess() {
    backgroundProcess = new BackgroundProcess(threadFactory, maxThreads);
  }

  private void verifyProgressionOfTime(BackgroundProcess backgroundProcess)
      throws Exception {
    MonotonicTimeSource timeSource =
        backgroundProcess.getAsynchronousMonotonicTimeSource();

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
    createBackgroundProcess();
  }

  @Test(expected = IllegalArgumentException.class) public void
  mustThrowIfMaxAllocationThreadsAreNotPositive() throws Exception {
    maxThreads = 0;
    createBackgroundProcess();
  }

  @Test public void
  mustAcceptMaxAllocationThreadsOfOne() throws Exception {
    maxThreads = 1;
    createBackgroundProcess();
  }

  @Test(timeout = TIMEOUT) public void
  mustNotRunAnyThreadsWhenReferenceCountIsZero() throws Exception {
    int firstCount = Thread.activeCount();
    createBackgroundProcess();
    int secondCount = Thread.activeCount();
    assertThat(secondCount, is(firstCount));
  }

  @Test(timeout = TIMEOUT) public void
  mustNotRunAnyThreadsAfterReferenceCountReachesZero()
      throws Exception {
    int firstCount = Thread.activeCount();
    createBackgroundProcess();
    backgroundProcess.incrementReferences();
    backgroundProcess.decrementReferences();
    int secondCount = Thread.activeCount();
    assertThat(secondCount, is(firstCount));
  }

  @Test(timeout = TIMEOUT) public void
  mustProvideTimeWhenReferenceCountIsZero() throws Exception {
    createBackgroundProcess();
    verifyProgressionOfTime(backgroundProcess);
  }

  @Test(timeout = TIMEOUT) public void
  mustProvideTimeWhenReferenceCountIsNonZero() throws Exception {
    createBackgroundProcess();
    backgroundProcess.incrementReferences();

    try {
      verifyProgressionOfTime(backgroundProcess);
    } finally {
      backgroundProcess.decrementReferences();
    }
  }

  @Test(timeout = TIMEOUT) public void
  mustProvideTimeWhenReferenceCountReturnsToZero()
      throws Exception {
    createBackgroundProcess();
    backgroundProcess.incrementReferences();
    Thread.sleep(30);
    backgroundProcess.decrementReferences();

    verifyProgressionOfTime(backgroundProcess);
  }

  @Test(timeout = TIMEOUT) public void
  mustCreateThreadsWithProvidedThreadFactory() throws Exception {
    createBackgroundProcess();
    backgroundProcess.incrementReferences();
    try {
      Thread thread = createdThreads.poll();
      assertThat(thread, is(not(nullValue())));
      assertThat(thread.getState(), isOneOf(
          State.RUNNABLE, State.TIMED_WAITING));
    } finally {
      backgroundProcess.decrementReferences();
    }
  }

  @Test(timeout = TIMEOUT) public void
  mustStopAllCreatedThreadsWhenReferenceCountGoesToZero()
      throws Exception {
    createBackgroundProcess();
    backgroundProcess.incrementReferences();
    waitForThreadState(createdThreads.peek(), State.RUNNABLE);
    backgroundProcess.decrementReferences();

    assertThat(createdThreads.size(), is(greaterThan(0)));
    createdThreads.forEach(th ->
        assertThat(th.getState(), is(State.TERMINATED)));
  }

  @Test(timeout = TIMEOUT) public void
  mustStopAllCreatedThreadsWhenReferenceCountGoesToZeroEvenWhenInterrupted()
      throws Exception {
    createBackgroundProcess();
    backgroundProcess.incrementReferences();
    waitForThreadState(createdThreads.peek(), State.RUNNABLE);
    Thread.currentThread().interrupt();
    backgroundProcess.decrementReferences();

    assertThat(createdThreads.size(), is(greaterThan(0)));
    createdThreads.forEach(th ->
        assertThat(th.getState(), is(State.TERMINATED)));
  }

  @Test(timeout = TIMEOUT) public void
  timerThreadMustNotStopEvenIfSpuriouslyInterrupted() throws Exception {
    createBackgroundProcess();
    backgroundProcess.incrementReferences();
    Thread timer = createdThreads.poll();
    waitForThreadState(timer, State.RUNNABLE);
    timer.interrupt();
    try {
      timer.join(20);
      State state = timer.getState();
      assertThat(state, isOneOf(State.RUNNABLE, State.TIMED_WAITING));
    } finally {
      backgroundProcess.decrementReferences();
    }
  }

  @Test(timeout = TIMEOUT) public void
  submittedTasksMustExecuteInThreadsFromTheGivenThreadFactory()
      throws Exception {
    BlockingQueue<Thread> queue = new LinkedBlockingQueue<>();
    createBackgroundProcess();
    backgroundProcess.incrementReferences();
    try {
      backgroundProcess.submit(() -> queue.offer(Thread.currentThread()));
      Thread taskThread = queue.take();
      assertThat(taskThread, isIn(createdThreads));
    } finally {
      backgroundProcess.decrementReferences();
    }
  }

  @Test(timeout = TIMEOUT) public void
  backgroundTaskExecutionMustWorkAfterRestart() throws Exception {
    createBackgroundProcess();
    backgroundProcess.incrementReferences();
    Semaphore semaphore = new Semaphore(0);
    backgroundProcess.submit(semaphore::release);
    semaphore.acquire();
    backgroundProcess.decrementReferences();

    backgroundProcess.incrementReferences();
    backgroundProcess.submit(semaphore::release);
    // Assert that we don't time out on the acquire here:
    semaphore.acquire();
    backgroundProcess.decrementReferences();
  }

  @Test(timeout = TIMEOUT) public void
  mustNotCreateMoreAllocationThreadsThanNeeded() throws Exception {
    createBackgroundProcess();
    backgroundProcess.incrementReferences();
    Semaphore semaphore = new Semaphore(0);
    try {
      int sleepTime = 10;
      int iterations = 10;
      for (int i = 0; i < iterations; i++) {
        backgroundProcess.submit(semaphore::release);
        semaphore.acquire();
        Thread.sleep(sleepTime);
      }
      assertThat(createdThreads.size(), lessThan(iterations));
    } finally {
      backgroundProcess.decrementReferences();
    }
  }

  @Test(timeout = TIMEOUT) public void
  mustScaleAllocationThreadsUpAsNeeded() throws Exception {
    // Issuing 10 allocations that each take 100 milliseconds to complete,
    // we should find that more than 5 threads are created... presumably
    createBackgroundProcess();
    backgroundProcess.incrementReferences();
    int threadCount = 10;
    long parkTimeNanos = MILLISECONDS.toNanos(100);
    Semaphore semaphore = new Semaphore(0);
    Runnable sleeper = () -> LockSupport.parkNanos(parkTimeNanos);
    for (int i = 0; i < threadCount; i++) {
      backgroundProcess.submit(sleeper);
    }
    backgroundProcess.submit(semaphore::release);
    semaphore.acquire();
    int size = createdThreads.size();
    backgroundProcess.decrementReferences();
    assertThat(size, greaterThan(3));
  }

  @Test(timeout = TIMEOUT) public void
  mustRunRecurringTaskUntilCancelled() throws Exception {
    createBackgroundProcess();
    backgroundProcess.incrementReferences();
    Semaphore semaphore = new Semaphore(0);
    long start = System.nanoTime();
    ScheduledJobTask task = backgroundProcess.scheduleWithFixedDelay(
        semaphore::release, 1, MILLISECONDS);
    semaphore.acquire(5);
    task.stop();
    long elapsedNanos = System.nanoTime() - start;
    int elapsedMillis = (int) TimeUnit.NANOSECONDS.toMillis(elapsedNanos);
    spinwait(10);
    backgroundProcess.decrementReferences();
    // The task has a tight schedule, so the cancel signal might come a few
    // runs late. So if we try to grab a few MORE permits than what should have
    // become available, then we should fail.
    assertFalse(semaphore.tryAcquire((elapsedMillis - 10) + 3));
  }

  @Test(timeout = TIMEOUT) public void
  mustRunRecurringTaskEvenIfItThrows() throws Exception {
    createBackgroundProcess();
    backgroundProcess.incrementReferences();
    Semaphore semaphore = new Semaphore(0);
    ScheduledJobTask task = backgroundProcess.scheduleWithFixedDelay(() -> {
      semaphore.release();
      throw new RuntimeException("boo");
    }, 1, MILLISECONDS);
    semaphore.acquire(2); // assert this doesn't time out
    task.stop();
    backgroundProcess.decrementReferences();
  }

  @Test(timeout = TIMEOUT) public void
  scheduledTasksMustStopWhenReferenceCountReachesZero() throws Exception {
    createBackgroundProcess();
    backgroundProcess.incrementReferences();
    AtomicInteger counter = new AtomicInteger();
    backgroundProcess.scheduleWithFixedDelay(
        counter::incrementAndGet, 1, MILLISECONDS);
    //noinspection StatementWithEmptyBody
    while (counter.get() < 5);
    backgroundProcess.decrementReferences();
    int a = counter.get();
    spinwait(5);
    int b = counter.get();
    assertThat(a, is(b));
  }

  @Test(timeout = TIMEOUT, expected = IllegalStateException.class) public void
  submittingScheduledTaskMustThrowIfBackgroundProcessHasBeenStopped()
      throws InterruptedException {
    createBackgroundProcess();
    backgroundProcess.incrementReferences();
    Semaphore semaphore = new Semaphore(0);
    backgroundProcess.scheduleWithFixedDelay(
        semaphore::release, 1, MILLISECONDS);
    semaphore.acquire();
    backgroundProcess.decrementReferences();
    backgroundProcess.scheduleWithFixedDelay(() -> {}, 1, MILLISECONDS);
  }
}
