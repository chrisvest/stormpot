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
 * A task node with a unit of work that should be executed in the background as
 * soon as possible.
 */
class ImmediateJobTask extends Task { // as opposed to scheduled job
  final Runnable runnable;

  /**
   * Construct the background task node with the given unit of work.
   * @param runnable The work that should be run in the background.
   */
  ImmediateJobTask(Runnable runnable) {
    super(false);
    this.runnable = runnable;
  }

  @Override
  void execute() {
    runnable.run();
  }
}
