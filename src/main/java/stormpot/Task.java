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

/**
 * The abstract class of tasks enqueued for background (or foreground)
 * processing by the {@link BackgroundScheduler}. This class is not used directly,
 * but internally by the BackgroundProcess.
 */
abstract class Task {
  private final boolean isForegroundWork;
  volatile Task next;

  /**
   * Construct the TaskNode, specifying whether the work should be run in the
   * foreground or in the background. Background work will be scheduled by the
   * {@link ProcessController} in an initialised
   * {@link BackgroundScheduler}.
   *
   * @param isForegroundWork {@code true} if this work should be run by the
   *                         threads that
   *                         {@link BackgroundScheduler#submit(Runnable) submit}
   *                         work to the BackgroundProcess, {@code false} if
   *                         the work should be scheduled to run in a background
   *                         thread by the ProcessController.
   */
  Task(boolean isForegroundWork) {
    this.isForegroundWork = isForegroundWork;
  }

  /**
   * {@code true} if the work represented by this task node should be performed
   * in the foreground by the next thread that
   * {@link BackgroundScheduler#submit(Runnable) submit} a task to the
   * BackgroundProcess.
   *
   * @return {@code true} if this is foreground work rather than background
   * work.
   */
  boolean isForegroundWork() {
    return isForegroundWork;
  }

  /**
   * Immediately execute the work contained in this task.
   * @param controller
   */
  abstract void execute(ProcessController controller);
}
