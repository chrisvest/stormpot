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
 * Dedicated time-keeping thread started by the {@link BackgroundProcess}
 * upon initialisation. Only one is kept around per BackgroundProcess, and
 * it stopped when the BackgroundProcess reference count reaches zero.
 *
 * The BackgroundProcess constructs an {@link AsynchronousMonotonicTimeSource}
 * and passes it to the TimeKeeper, which then updates the time source on
 * regular intervals.
 */
final class TimeKeeper implements Runnable {
  private static final long SLEEP_TIME = 10;

  private final AsynchronousMonotonicTimeSource timeSource;
  private volatile boolean stopped;

  TimeKeeper(AsynchronousMonotonicTimeSource timeSource) {
    this.timeSource = timeSource;
  }

  @Override
  public void run() {
    timeSource.setAsync(true);
    while (!stopped) {
      timeSource.updateTime();
      sleep();
    }
    timeSource.setAsync(false);
  }

  private void sleep() {
    try {
      // Sleep for 10 milliseconds. This is approximately the time-slice on
      // Linux and Windows. A lower sleep time will cause Windows to change
      // the system-wide scheduling quantum to a value small enough to
      // accurately hit the sleep time. So let's not do that.
      Thread.sleep(SLEEP_TIME);
    } catch (InterruptedException ignore) {
      // Either a stray interrupt, or a signal to re-check the stopped flag
    }
  }

  /**
   * Signal that the time-keeping thread should stop.
   */
  void stop() {
    stopped = true;
  }
}
