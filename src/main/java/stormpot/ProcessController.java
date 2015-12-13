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
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.ThreadFactory;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.function.Supplier;

class ProcessController implements Runnable {
  private final Function<TaskNode,TaskNode> getAndSetTaskStack;
  private final Supplier<TaskNode> controlProcessInitialiser;
  private final ThreadFactory factory;
  private final int maxThreads;
  private final BlockingQueue<TaskNode> workQueue;
  private final Collection<BackgroundWorker> workers;
  private final Consumer<BackgroundWorker> workerTerminationCallback;
  private volatile boolean stopped;
  private volatile BlockedTaskNode blockedTaskNode;

  ProcessController(
      Function<TaskNode, TaskNode> getAndSetTaskStack,
      Supplier<TaskNode> controlProcessInitialiser,
      ThreadFactory factory,
      int maxThreads) {
    this.getAndSetTaskStack = getAndSetTaskStack;
    this.controlProcessInitialiser = controlProcessInitialiser;
    this.factory = factory;
    this.maxThreads = maxThreads;
    workers = new ArrayList<>();
    workQueue = new LinkedBlockingQueue<>();
    workerTerminationCallback = workers::remove;
  }

  @Override
  public void run() {
    blockedTaskNode = new BlockedTaskNode(Thread.currentThread());

    do {
      TaskNode task = getAndSetTaskStack(blockedTaskNode);
      processTasks(task);
      blockedTaskNode.park(this);
    } while (!stopped);

    TaskNode task = getAndSetTaskStack(controlProcessInitialiser.get());
    processTasks(task);
    workers.forEach(BackgroundWorker::stop);
  }

  private void processTasks(TaskNode task) {
    while (task != null) {
      if (!task.isForegroundWork()) {
        execute(task);
      }
      task = task.next;
    }
  }

  private void execute(TaskNode task) {
    workQueue.offer(task);
    int workerCount = workers.size();
    if (workerCount == 0 || (workQueue.size() > (workerCount + 2) && workerCount < maxThreads)) {
      boolean allowWorkerSelftTermination = workerCount == 0;
      BackgroundWorker worker = new BackgroundWorker(
          workQueue,
          allowWorkerSelftTermination,
          workerTerminationCallback);
      workers.add(worker);
      Thread thread = factory.newThread(worker);
      thread.start();
    }
  }

  private TaskNode getAndSetTaskStack(TaskNode taskNode) {
    return getAndSetTaskStack.apply(taskNode);
  }

  void stop() {
    stopped = true;
    BlockedTaskNode node = blockedTaskNode;
    if (node != null) {
      node.execute();
    }
  }
}
