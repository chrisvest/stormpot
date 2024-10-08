/*
 * Copyright © 2011-2024 Chris Vest (mr.chrisvest@gmail.com)
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package stormpot.internal;

/**
 * Convenience functions for operating on relative nanosecond time spans.
 */
public final class NanoClock {
  private NanoClock() {
  }

  /**
   * Equivalent of {@link System#nanoTime()}.
   * @return The current timestamp in nanoseconds.
   * @see System#nanoTime()
   */
  public static long nanoTime() {
    return System.nanoTime();
  }

  /**
   * Compute the number of nanoseconds passed since the given start time.
   *
   * @param startNanos The starting timestamp, obtained from {@link #nanoTime()}.
   * @return The elapsed time since the start time, in nanoseconds.
   */
  public static long elapsed(long startNanos) {
    return nanoTime() - startNanos;
  }

  /**
   * Compute the remaining time of the given timeout, from the given starting time.
   * @param startNanos The starting time, obtained from {@link #nanoTime()}.
   * @param timeout The timeout in nanoseconds.
   * @return The (possibly negative) remaining nanoseconds of the timeout.
   */
  static long timeoutLeft(long startNanos, long timeout) {
    return timeout - (nanoTime() - startNanos);
  }
}
