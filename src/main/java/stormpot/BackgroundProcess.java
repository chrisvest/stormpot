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

import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReferenceFieldUpdater;

import static java.util.concurrent.atomic.AtomicReferenceFieldUpdater.newUpdater;

public final class BackgroundProcess {
  private static final AtomicReferenceFieldUpdater<BackgroundProcess, Task> U =
      newUpdater(BackgroundProcess.class, Task.class, "taskStack");

  private final ThreadFactory factory;
  private final int maxThreads;
  private final AsynchronousMonotonicTimeSource timeSource;

  @SuppressWarnings("unused") // Accessed through Unsafe or ARFU
  private volatile Task taskStack;

  private int referenceCount;
  private TimeKeeper timeKeeper;
  private ProcessController processController;
  private Thread timeKeeperThread;
  private Thread processControllerThread;

  public BackgroundProcess(ThreadFactory factory, int maxAllocationThreads) {
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
    getAndSetTaskStack(createControlProcessInitialiseTask());
  }

  private StartControlThreadTask createControlProcessInitialiseTask() {
    return new StartControlThreadTask(this::startControlThread);
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
  }

  synchronized void decrementReferences() {
    referenceCount--;
    assert referenceCount >= 0: "Negative reference count";
    if (referenceCount == 0) {
      deinitialise();
    }
  }

  private void deinitialise() {
    if (processController != null) {
      processController.stop();
      join(processControllerThread);
    }
    timeKeeper.stop();
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
   * fwe tens of nanoseconds, to a couple of microseconds, depending on the
   * operating system. While the time source returned by this method will have
   * a precision of about 10 milliseconds. This precision is good enough for
   * the use cases in the Stormpot internals, but might not be good enough
   * in other places where {@code System.nanoTime()} is used.
   * @return an asynchronous {@link MonotonicTimeSource} implementation.
   */
  public MonotonicTimeSource getAsynchronousMonotonicTimeSource() {
    return timeSource;
  }

  private synchronized void startControlThread() {
    processController = new ProcessController(
        this::getAndSetTaskStack,
        this::createControlProcessInitialiseTask,
        factory,
        timeSource,
        maxThreads);
    processControllerThread = factory.newThread(processController);
    processControllerThread.start();
  }

  void submit(Runnable runnable) {
    enqueue(new ImmediateJobTask(runnable));
  }

  private void enqueue(Task task) {
    Task prev = getAndSetTaskStack(task);
    task.next = prev;
    if (prev.isForegroundWork()) {
      prev.execute(processController);
    }
  }

  private Task getAndSetTaskStack(Task replacement) {
    return U.getAndSet(this, replacement);
  }

  public ScheduledJobTask scheduleWithFixedDelay(
      Runnable runnable, long delay, TimeUnit unit) {
    ScheduledJobTask task = new ScheduledJobTask(runnable, delay, unit);
    enqueue(task);
    return task;
  }
}
