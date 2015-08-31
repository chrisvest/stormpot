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
 * A Completion represents some task that is going to be completed at some
 * point in the future, or maybe already has completed. It is similar to
 * {@link java.util.concurrent.Future Future} but without any options for
 * cancellation or returning a result. Indeed, you cannot even precisely tell
 * if the task has already completed, but the await methods will return
 * immediately if that is the case.
 * @author Chris Vest <mr.chrisvest@gmail.com>
 * @see Pool#shutdown()
 */
public interface Completion {
  
  /**
   * Causes the current thread to wait until the completion is finished,
   * or the thread is {@link Thread#interrupt() interrupted}, or the specified
   * waiting time elapses.
   *
   * If the task represented by this completion has already completed,
   * the method immediately returns `true`.
   *
   * If the current thread already has its interrupted status set upon entry
   * to this method, or the thread is interrupted while waiting, then an
   * {@link InterruptedException} is thrown and the current threads interrupted
   * status is cleared.
   *
   * If the specified waiting time elapses, then the method returns `false`.
   * @param timeout The timeout delimiting the maximum time to wait for the
   * task to complete. Timeouts with zero or negative values will cause the
   * method to return immediately.
   * @return `true` if the task represented by this completion
   * completed within the specified waiting time, or was already complete upon
   * entry to this method; or `false` if the specified Timeout
   * elapsed before the task could finished.
   * @throws InterruptedException if the current thread is interrupted while
   * waiting.
   * @throws IllegalArgumentException if the provided `timeout` parameter is
   * `null`.
   */
  boolean await(Timeout timeout) throws InterruptedException;
}
