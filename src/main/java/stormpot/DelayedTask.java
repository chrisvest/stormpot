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

import java.util.concurrent.TimeUnit;

class DelayedTask extends ScheduledJobTask implements Comparable<DelayedTask> {
  private final long deadline;

  public DelayedTask(
      Runnable runnable, long deadline, long delay, TimeUnit unit) {
    super(runnable, delay, unit);
    this.deadline = deadline;
  }

  @Override
  public int compareTo(DelayedTask o) {
    return Long.compare(deadline, o.deadline);
  }

  long getDeadline() {
    return deadline;
  }
}
