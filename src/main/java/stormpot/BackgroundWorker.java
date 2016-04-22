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

import java.util.concurrent.BlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;

final class BackgroundWorker implements Runnable {
  private final BlockingQueue<Task> queue;
  private final boolean allowWorkerSelfTermination;
  private final Consumer<BackgroundWorker> workerTerminationCallback;
  private final long pollTimeoutMillis;
  private final ProcessController controller;
  private volatile boolean stopped;
  private volatile Thread workerThread;

  BackgroundWorker(
      BlockingQueue<Task> queue,
      boolean allowWorkerSelfTermination,
      Consumer<BackgroundWorker> workerTerminationCallback,
      ProcessController controller) {
    this.queue = queue;
    this.allowWorkerSelfTermination = allowWorkerSelfTermination;
    this.workerTerminationCallback = workerTerminationCallback;
    this.controller = controller;
    pollTimeoutMillis = allowWorkerSelfTermination?
        TimeUnit.MINUTES.toMillis(1) : TimeUnit.MINUTES.toMillis(15);
  }

  @Override
  public void run() {
    workerThread = Thread.currentThread();
    while (!stopped) {
      try {
        Task task = queue.poll(pollTimeoutMillis, TimeUnit.MILLISECONDS);

        if (task != null) {
          task.execute(controller);
        } else if (allowWorkerSelfTermination) {
          // Nothing for us to do. Let's terminate.
          break;
        }

      } catch (Exception ignore) {
      }
    }
    workerTerminationCallback.accept(this);
  }

  void stop() {
    stopped = true;
    Thread thread = workerThread;
    if (thread != null) {
      thread.interrupt();
    }
  }
}
