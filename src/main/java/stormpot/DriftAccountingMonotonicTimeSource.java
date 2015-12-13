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

// TODO testing
class DriftAccountingMonotonicTimeSource implements MonotonicTimeSource {
  // TODO add padding to ensure these fields end up on a private cache line
  private volatile long currentTimeNanos;
  private volatile boolean async;

  @Override
  public long nanoTime() {
    if (async) {
      return currentTimeNanos;
    } else {
      return System.nanoTime();
    }
  }

  void setAsync(boolean makeAsync) {
    async = makeAsync;
  }

  void updateTime(long nanoTime, long sleepTime) {
    // TODO use the sleep time to do controlled adjustments and drifting when
    // TODO the clock moves backwards
    long last = currentTimeNanos;
    long diff = nanoTime - last;
    // Explicitly check for 0, because that's a signal to turn off async time.
    if (diff == 0 && nanoTime != 0) {
      // We require a positive diff to uphold monotonicity. System.nanoTime
      // should already be providing us a monotonic clock, but on OS X that
      // clock can be observed to stand still between two consecutive calls.
      // To cope with this, we add one nanosecond to our previous value.
      // If System.nanoTime surprises us and _does_ move backwards, we
      // unfortunately have to break our monotonicity contract here, because
      // we can't trust the clock at this point, and thus we have no idea how
      // much time we'd otherwise spend drifting the clock.
      currentTimeNanos = last + 1;
    } else {
      currentTimeNanos = nanoTime;
    }
  }
}
