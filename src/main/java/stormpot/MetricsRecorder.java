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
 * A MetricsRecorder implementation supplies the pool with the ability to
 * record and report operational metrics. Many of the methods of the
 * {@link stormpot.ManagedPool} interface relies on a MetricsRecorder being
 * configured for the given pool. Stormpot provides no default implementation
 * of this interface, so by default many of the metrics reporting methods of
 * the ManagedPool interfaces will return Double.NaN.
 *
 * NOTE: that implementations of this class must be thread-safe!
 *
 * Here's an example implementation based on
 * http://metrics.codahale.com/[Coda Hale's Metrics library]:
 *
 * [source,java]
 * --
 * include::src/test/java/stormpot/examples/CodaHaleMetricsRecorder.java[lines=16..-1]
 * --
 *
 * @since 2.3
 */
public interface MetricsRecorder {

  /**
   * Record a latency sample of a successful allocation, in milliseconds.
   * This is the time it took for a call to {@link Allocator#allocate(Slot)}
   * to return a useable object.
   * @param milliseconds How many milliseconds it took to successfully
   *                     allocate an object.
   */
  void recordAllocationLatencySampleMillis(long milliseconds);

  /**
   * Record a latency sample of a failed allocation, in milliseconds. This is
   * the time it took for a call to {@link Allocator#allocate(Slot)} to throw
   * an exception or return `null`.
   * @param milliseconds How many milliseconds transpired before the allocation
   *                     failed.
   */
  void recordAllocationFailureLatencySampleMillis(long milliseconds);

  /**
   * Record a latency sample of a deallocation, in milliseconds. This is the
   * time it took to complete a call to {@link Allocator#deallocate(Poolable)}.
   * @param milliseconds How many milliseconds to took to deallocate an object.
   */
  void recordDeallocationLatencySampleMillis(long milliseconds);

  /**
   * Record a latency sample of a successful reallocation, in milliseconds.
   * This is the time it took for a call to
   * {@link Reallocator#reallocate(Slot, Poolable)} to return a useable object.
   * @param milliseconds How many milliseconds it took to successfully
   *                     reallocate an object.
   */
  void recordReallocationLatencySampleMillis(long milliseconds);

  /**
   * Record a latency sample of a failed reallocation, in milliseconds. This is
   * the time it took for a call to
   * {@link Reallocator#reallocate(Slot, Poolable)} to throw an exception or
   * return `null`.
   * @param milliseconds How many milliseconds transpired before the
   *                     reallocation failed.
   */
  void recordReallocationFailureLatencySampleMillis(long milliseconds);

  /**
   * Record an object lifetime sample, in milliseconds. This is the time that
   * transpired from an object was allocated with
   * {@link Allocator#allocate(Slot)}, till it was deallocated with
   * {@link Allocator#deallocate(Poolable)}.
   * @param milliseconds How many milliseconds a Poolable object was in
   *                     circulation; from it was created, till it was
   *                     deallocated.
   */
  void recordObjectLifetimeSampleMillis(long milliseconds);

  /**
   * Get the approximate Poolable object allocation latency value, in
   * milliseconds, of the given percentile/quantile of the values recorded so
   * far.
   *
   * A percentile, or quantile, is a value between 0.0 and 1.0, both inclusive,
   * which represents a percentage of the recorded samples. In other words,
   * given a percentile, the returned value will be the latency in
   * milliseconds, that the given percent of samples is less than or equal to.
   * For instance, if given the percentile 0.9 returns 120, then 90% of all
   * recorded latency samples will be less than or equal to 120 milliseconds.
   *
   * Note: Implementers should strive to return `Double.NaN` as a sentinel
   * value, if they do not support recording of the allocation latencies and/or
   * returning a specific percentile of such recordings.
   * @param percentile The percentile/quantile to get a value for.
   * @return The latency value in milliseconds for the given
   * percentile/quantile.
   */
  double getAllocationLatencyPercentile(double percentile);

  /**
   * Get the approximate latency value, in milliseconds, for failed allocation
   * latencies within the given percentile/quantile of what has been recorded
   * so far.
   *
   * A percentile, or quantile, is a value between 0.0 and 1.0, both inclusive,
   * which represents a percentage of the recorded samples. In other words,
   * given a percentile, the returned value will be the latency in
   * milliseconds, that the given percent of samples is less than or equal to.
   * For instance, if given the percentile 0.9 returns 120, then 90% of all
   * recorded latency samples will be less than or equal to 120 milliseconds.
   *
   * Note: Implementers should strive to return `Double.NaN` as a sentinel
   * value, if they do not support recording of the allocation latencies and/or
   * returning a specific percentile of such recordings.
   * @param percentile The percentile/quantile to get a value for.
   * @return The latency value in milliseconds for the given
   * percentile/quantile.
   */
  double getAllocationFailureLatencyPercentile(double percentile);

  /**
   * Get the approximate latency value, in milliseconds, for deallocation
   * latencies within the given percentile/quantile of what has been recorded
   * so far.
   *
   * A percentile, or quantile, is a value between 0.0 and 1.0, both inclusive,
   * which represents a percentage of the recorded samples. In other words,
   * given a percentile, the returned value will be the latency in
   * milliseconds, that the given percent of samples is less than or equal to.
   * For instance, if given the percentile 0.9 returns 120, then 90% of all
   * recorded latency samples will be less than or equal to 120 milliseconds.
   *
   * Note: Implementers should strive to return `Double.NaN` as a sentinel
   * value, if they do not support recording of the allocation latencies and/or
   * returning a specific percentile of such recordings.
   * @param percentile The percentile/quantile to get a value for.
   * @return The latency value in milliseconds for the given
   * percentile/quantile.
   */
  double getDeallocationLatencyPercentile(double percentile);

  /**
   * Get the approximate latency value, in milliseconds, for reallocation
   * latencies within the given percentile/quantile of what has been recorded
   * so far.
   *
   * A percentile, or quantile, is a value between 0.0 and 1.0, both inclusive,
   * which represents a percentage of the recorded samples. In other words,
   * given a percentile, the returned value will be the latency in
   * milliseconds, that the given percent of samples is less than or equal to.
   * For instance, if given the percentile 0.9 returns 120, then 90% of all
   * recorded latency samples will be less than or equal to 120 milliseconds.
   *
   * Note: Implementers should strive to return `Double.NaN` as a sentinel
   * value, if they do not support recording of the allocation latencies and/or
   * returning a specific percentile of such recordings.
   * @param percentile The percentile/quantile to get a value for.
   * @return The latency value in milliseconds for the given
   * percentile/quantile.
   */
  double getReallocationLatencyPercentile(double percentile);

  /**
   * Get the approximate latency value, in milliseconds, for failed
   * reallocation latencies within the given percentile/quantile of what has
   * been recorded so far.
   *
   * A percentile, or quantile, is a value between 0.0 and 1.0, both inclusive,
   * which represents a percentage of the recorded samples. In other words,
   * given a percentile, the returned value will be the latency in
   * milliseconds, that the given percent of samples is less than or equal to.
   * For instance, if given the percentile 0.9 returns 120, then 90% of all
   * recorded latency samples will be less than or equal to 120 milliseconds.
   *
   * Note: Implementers should strive to return `Double.NaN` as a sentinel
   * value, if they do not support recording of the allocation latencies and/or
   * returning a specific percentile of such recordings.
   * @param percentile The percentile/quantile to get a value for.
   * @return The latency value in milliseconds for the given
   * percentile/quantile.
   */
  double getReallocationFailurePercentile(double percentile);

  /**
   * Get the approximate object lifetime, in milliseconds, for the given
   * percentile/quantile.
   *
   * A percentile, or quantile, is a value between 0.0 and 1.0, both inclusive,
   * which represents a percentage of the recorded samples. In other words,
   * given a percentile, the returned value will be the latency in
   * milliseconds, that the given percent of samples is less than or equal to.
   * For instance, if given the percentile 0.9 returns 120, then 90% of all
   * recorded latency samples will be less than or equal to 120 milliseconds.
   *
   * Note: Implementers should strive to return `Double.NaN` as a sentinel
   * value, if they do not support recording of the allocation latencies and/or
   * returning a specific percentile of such recordings.
   * @param percentile The percentile/quantile to get a value for.
   * @return The latency value in milliseconds for the given
   * percentile/quantile.
   */
  double getObjectLifetimePercentile(double percentile);
}
