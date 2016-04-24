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

import java.util.ArrayList;
import java.util.Collection;
import java.util.concurrent.*;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.function.Supplier;

final class ProcessController implements Runnable {
  private static final long DEFAULT_PARK_TIME_NANOS =
      TimeUnit.MILLISECONDS.toNanos(10);

  private final Function<Task, Task> getAndSetTaskStack;
  private final Supplier<Task> controlProcessInitialiser;
  private final ThreadFactory factory;
  private final PriorityBlockingQueue<DelayedTask> delayedTasks;
  private final AsynchronousMonotonicTimeSource timeSource;
  private final int maxThreads;
  private final BlockingQueue<Task> workQueue;
  private final Collection<BackgroundWorker> workers;
  private final Consumer<BackgroundWorker> workerTerminationCallback;
  private volatile boolean stopped;
  private volatile BlockedTask blockedTaskNode;

  ProcessController(
      Function<Task, Task> getAndSetTaskStack,
      Supplier<Task> controlProcessInitialiser,
      ThreadFactory factory,
      AsynchronousMonotonicTimeSource timeSource,
      int maxThreads) {
    this.getAndSetTaskStack = getAndSetTaskStack;
    this.controlProcessInitialiser = controlProcessInitialiser;
    this.factory = factory;
    this.delayedTasks = new PriorityBlockingQueue<>();
    this.timeSource = timeSource;
    this.maxThreads = maxThreads;
    workers = new ArrayList<>();
    workQueue = new LinkedBlockingQueue<>();
    workerTerminationCallback = workers::remove;
  }

  @Override
  public void run() {
    blockedTaskNode = new BlockedTask(Thread.currentThread());

    do {
      Task task = getAndSetTaskStack(blockedTaskNode);
      processTasks(task);
      long parkTimeNanos = DEFAULT_PARK_TIME_NANOS;
      DelayedTask topDelayedTask = delayedTasks.peek();
      if (topDelayedTask != null) {
        long diff = timeSource.nanoTime() - topDelayedTask.getDeadline();
        if (diff > 0) {
          delayedTasks.poll();
          execute(topDelayedTask);
        }
      }
      blockedTaskNode.park(this, parkTimeNanos);
    } while (!stopped);

    Task task = getAndSetTaskStack(controlProcessInitialiser.get());
    processTasks(task);
    workers.forEach(BackgroundWorker::stop);
  }

  private void processTasks(Task task) {
    while (task != null) {
      if (!task.isForegroundWork()) {
        execute(task);
      }
      task = task.next;
    }
  }

  private void execute(Task task) {
    workQueue.offer(task);
    int workerCount = workers.size();
    if (workerCount == 0 || needMoreThreads(workerCount)) {
      boolean allowWorkerSelfTermination = workerCount == 0;
      BackgroundWorker worker = new BackgroundWorker(
          workQueue,
          allowWorkerSelfTermination,
          workerTerminationCallback,
          this);
      workers.add(worker);
      Thread thread = factory.newThread(worker);
      thread.start();
    }
  }

  void enqueueDelayed(Runnable runnable, long delay, TimeUnit unit) {
    long deadline = timeSource.nanoTime() + unit.toNanos(delay);
    delayedTasks.add(new DelayedTask(runnable, deadline, delay, unit));
  }

  private boolean needMoreThreads(int workerCount) {
    return workQueue.size() > (workerCount + 2) && workerCount < maxThreads;
  }

  private Task getAndSetTaskStack(Task task) {
    return getAndSetTaskStack.apply(task);
  }

  void stop() {
    stopped = true;
    BlockedTask node = blockedTaskNode;
    if (node != null) {
      node.execute(this);
    }
  }
}
