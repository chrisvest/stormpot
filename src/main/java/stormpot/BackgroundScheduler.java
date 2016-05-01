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

import java.util.concurrent.*;

/**
 * The {@link BackgroundScheduler} is a thread-pool that can be shared among
 * many Stormpot {@link Pool} instance, allowing them to schedule and run work
 * in the background.
 *
 * The {@link BackgroundScheduler} is also in charge of maintaining a
 * {@link MonotonicTimeSource}, which is used by the pool for telling time.
 * This time source is for instance used for time-based expiration checking of
 * objects in the pool, and for {@link Pool#claim(Timeout)} timeouts.
 */
public final class BackgroundScheduler {
  private static BackgroundScheduler DEFAULT_INSTANCE;

  private final ThreadFactory factory;
  private final int maxThreads;
  private final AsynchronousMonotonicTimeSource timeSource;

  private volatile int referenceCount;
  private TimeKeeper timeKeeper;
  private Thread timeKeeperThread;
  private ScheduledExecutorService executor;

  /**
   * Get the (shared) default {@link BackgroundScheduler} instance.
   *
   * @return The {@link BackgroundScheduler} that Stormpot pools will use
   * unless configured otherwise.
   */
  public static synchronized BackgroundScheduler getDefaultInstance() {
    if (DEFAULT_INSTANCE == null) {
      DEFAULT_INSTANCE = new BackgroundScheduler(
          StormpotThreadFactory.INSTANCE,
          Runtime.getRuntime().availableProcessors());
    }
    return DEFAULT_INSTANCE;
  }

  /**
   * Make the given {@link BackgroundScheduler} instance the new default
   * instance that {@link #getDefaultInstance()} will return, and that new
   * {@link Config} objects will start out being configured with.
   *
   * Note that this does not change the configuration of any existing
   * {@link Pool} or {@link Config} instance.
   *
   * @param scheduler The new default {@link BackgroundScheduler} instance.
   */
  public static synchronized void setDefaultInstance(
      BackgroundScheduler scheduler) {
    if (scheduler == null) {
      throw new IllegalArgumentException(
          "The default BackgroundScheduler cannot be set to null");
    }
    DEFAULT_INSTANCE = scheduler;
  }

  /**
   * Create a new {@link BackgroundScheduler} instance with the given
   * {@link ThreadFactory} and given max thread count.
   *
   * @param factory The {@link ThreadFactory} that the
   * {@link BackgroundScheduler} will use to create its background threads.
   * @param maxAllocationThreads The maximum number of background threads the
   * scheduler will have running at any point in time.
   */
  public BackgroundScheduler(ThreadFactory factory, int maxAllocationThreads) {
    if (factory == null) {
      throw new IllegalArgumentException("factory cannot be null.");
    }
    if (maxAllocationThreads < 1) {
      throw new IllegalArgumentException(
          "maxAllocationThreads must be positive.");
    }
    this.factory = factory;
    this.maxThreads = maxAllocationThreads;
    timeSource = new AsynchronousMonotonicTimeSource();
  }

  synchronized void incrementReferences() {
    if (referenceCount == 0) {
      initialise();
    }
    referenceCount++;
  }

  private void initialise() {
    timeKeeper = new TimeKeeper(timeSource);
    timeKeeperThread = factory.newThread(timeKeeper);
    timeKeeperThread.start();
    executor = Executors.newScheduledThreadPool(maxThreads, factory);
  }

  synchronized void decrementReferences() {
    referenceCount--;
    assert referenceCount >= 0 : "Negative reference count";
    if (referenceCount == 0) {
      deinitialise();
    }
  }

  private void deinitialise() {
    timeKeeper.stop();
    timeKeeperThread.interrupt();
    executor.shutdown();
    executor = null;
    join(timeKeeperThread);
  }

  private void join(Thread thread) {
    try {
      thread.join();
    } catch (InterruptedException ignore) {
      interruptedJoinThread(thread);
    }
  }

  private void interruptedJoinThread(Thread thread) {
    try {
      thread.join();
    } catch (InterruptedException e) {
      throw new IllegalStateException(
          "Interrupted while deinitialising BackgroundProcess", e);
    } finally {
      Thread.currentThread().interrupt();
    }
  }

  /**
   * Get a {@link MonotonicTimeSource} instance where the time value is updated
   * asynchronously in a background thread. This makes reading out the current
   * time a much faster operation, at the cost of reduced precision.
   *
   * {@link System#nanoTime()} typically has a precision of somewhere between a
   * few tens of nanoseconds, to a couple of microseconds, depending on the
   * operating system. While the time source returned by this method will have
   * a precision of about 10 milliseconds. This precision is good enough for
   * the use cases in the Stormpot internals, but might not be good enough
   * in other places where {@code System.nanoTime()} is used.
   *
   * @return an asynchronous {@link MonotonicTimeSource} implementation.
   */
  public MonotonicTimeSource getAsynchronousMonotonicTimeSource() {
    return timeSource;
  }

  ThreadFactory getThreadFactory() {
    return factory;
  }

  int getMaxThreads() {
    return maxThreads;
  }

  void submit(Runnable runnable) {
    checkIfStopped();
    executor.submit(runnable);
  }

  private void checkIfStopped() {
    if (referenceCount < 1) {
      throw new IllegalStateException(
          "Background process is not running; reference count is zero.");
    }
  }

  Stoppable scheduleWithFixedDelay(
      Runnable runnable, long delay, TimeUnit unit) {
    checkIfStopped();
    Runnable task = () -> {
      try {
        runnable.run();
      } catch (RuntimeException e) {
        e.printStackTrace();
      }
    };
    ScheduledFuture<?> future = executor.scheduleWithFixedDelay(task, 0, delay, unit);
    return () -> future.cancel(true);
  }

  interface Stoppable {
    void stop();
  }
}
