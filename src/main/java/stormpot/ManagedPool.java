/*
 * Copyright © 2011-2019 Chris Vest (mr.chrisvest@gmail.com)
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
package stormpot;

import javax.management.MXBean;
import java.util.concurrent.ConcurrentLinkedQueue;

/**
 * This is the JMX management interface for Stormpot object pools.
 *
 * Using this interface, pools can be exposed to external management as an
 * MXBean. Since its an MXBean, and not just an MBean, it imposes no
 * special requirements on 3rd party JMX integrators.
 *
 * Once you have created your pool, it is easy to expose it through the platform
 * MBeanServer, or any MBeanServer you like:
 *
 * [source,java,indent=0]
 * ----
 * include::src/test/java/examples/Examples.java[tag=managedPoolExample]
 * ----
 *
 * Using the platform MBeanServer will make the pool visible to tools like
 * JConsole and VisualVM.
 * @since 2.3
 */
@MXBean
public interface ManagedPool {
  /**
   * Return the number of objects the pool has allocated since it was created.
   *
   * @return The number of Poolable objects ever created by this pool.
   */
  long getAllocationCount();

  /**
   * Return the number of allocations that has failed, either because the
   * allocator threw an exception or because it returned null, since the pool
   * was created.
   *
   * @return The number of allocations that have failed for one reason or
   * another.
   */
  long getFailedAllocationCount();

  /**
   * If the pool is capable of precise object leak detection, this method will
   * return the number of object leaks that have been detected, and prevented,
   * since the pool was created. If the pool does not support precise object
   * leak detection, then this method returns -1.
   *
   * There are two kinds of leaks: One where the application forgets to release
   * an object back to the pool, but keeps a strong reference to the object,
   * and another where the application not only forgets to release the object,
   * but also looses the reference to the object, making it eligible for
   * garbage collection. The precise leak detector will only count leaks of the
   * latter kind, where the leaked object has been garbage collected.
   *
   * @return The number of objects leaked from the users of this pool, since
   * the pool was created, or -1 if the pool does not implement precise leak
   * detection.
   */
  long getLeakedObjectsCount();

  /**
   * Set a new target size of the pool.
   *
   * @param size The new target size.
   * @see Pool#setTargetSize(int)
   */
  void setTargetSize(int size);

  /**
   * Get the current target size of the pool.
   *
   * @return The current target size.
   * @see Pool#getTargetSize()
   */
  int getTargetSize();

  /**
   * Returns 'true' if the shut down process has been started on this pool,
   * 'false' otherwise. This method does not reveal whether or not the shut
   * down process has completed.
   * @return 'true' if {@link Pool#shutdown()} has been called on this pool.
   */
  boolean isShutDown();

  /**
   * Get the approximate object lifetime, in milliseconds, for the given
   * percentile/quantile.
   *
   * @see stormpot.MetricsRecorder#getObjectLifetimePercentile(double)
   * @param percentile The percentile to get, as a decimal, e.g. a number
   *                   between 0.0 and 1.0.
   * @return The approximate object lifetime in milliseconds, for the given
   * percentile, or Double.NaN if no {@link stormpot.MetricsRecorder} has been
   * configured for the pool.
   */
  double getObjectLifetimePercentile(double percentile);

  /**
   * Get the approximate Poolable object allocation latency value, in
   * milliseconds, of the given percentile/quantile of the values recorded so
   * far.
   *
   * @see stormpot.MetricsRecorder#getAllocationLatencyPercentile(double)
   * @param percentile The percentile to get, as a decimal, e.g. a number
   *                   between 0.0 and 1.0.
   * @return The approximate latency for allocations in milliseconds, for the
   * given percentile, or Double.NaN if no {@link stormpot.MetricsRecorder}
   * has been configured for the pool.
   */
  double getAllocationLatencyPercentile(double percentile);

  /**
   * Get the approximate latency value, in milliseconds, for failed allocation
   * latencies within the given percentile/quantile of what has been recorded
   * so far.
   *
   * @see stormpot.MetricsRecorder#getAllocationFailureLatencyPercentile(double)
   * @param percentile The percentile to get, as a decimal, e.g. a number
   *                   between 0.0 and 1.0.
   * @return The approximate latency for failed allocations in milliseconds,
   * for the given percentile, or Double.NaN if no
   * {@link stormpot.MetricsRecorder} has been configured for the pool.
   */
  double getAllocationFailureLatencyPercentile(double percentile);

  /**
   * Get the approximate latency value, in milliseconds, for reallocation
   * latencies within the given percentile/quantile of what has been recorded
   * so far.
   *
   * @see stormpot.MetricsRecorder#getReallocationLatencyPercentile(double)
   * @param percentile The percentile to get, as a decimal, e.g. a number
   *                   between 0.0 and 1.0.
   * @return The approximate latency for reallocations in milliseconds, for the
   * given percentile, or Double.NaN if no
   * {@link stormpot.MetricsRecorder} has been configured for the pool.
   */
  double getReallocationLatencyPercentile(double percentile);

  /**
   * Get the approximate latency value, in milliseconds, for failed
   * reallocation latencies within the given percentile/quantile of what has
   * been recorded so far.
   *
   * @see stormpot.MetricsRecorder#getReallocationFailurePercentile(double)
   * @param percentile The percentile to get, as a decimal, e.g. a number
   *                   between 0.0 and 1.0.
   * @return The approximate latency for failed reallocations in milliseconds,
   * for the given percentile, or Double.NaN if no
   * {@link stormpot.MetricsRecorder} has been configured for the pool.
   */
  double getReallocationFailureLatencyPercentile(double percentile);

  /**
   * Get the approximate latency value, in milliseconds, for deallocation
   * latencies within the given percentile/quantile of what has been recorded
   * so far.
   *
   * @see stormpot.MetricsRecorder#getDeallocationLatencyPercentile(double)
   * @param percentile The percentile to get, as a decimal, e.g. a number
   *                   between 0.0 and 1.0.
   * @return The approximate latency for deallocations, regardless of whether
   * the throw exceptions or not, in milliseconds for the given percentile, or
   * Double.NaN if no {@link stormpot.MetricsRecorder} has been configured for
   * the pool.
   */
  double getDeallocationLatencyPercentile(double percentile);
  
  /**
   * Get the approximate number of currently allocated objects.
   *
   * This operation has time complexity O(poolSize) to count the number of slots.
   *
   * The default implementation of this interface methods returns {@code -1}.
   *
   * @return The current approximate number of allocated objects.
   */
  default int getAllocatedSize() {
    return -1;
  }
  
  /**
   * Get the approximate number of objects currently in use.
   *
   * This operation has time complexity O(poolSize) to check each slot.
   *
   * The counting operation is "weakly consistent",
   * in a similar sense to {@link ConcurrentLinkedQueue#iterator()}.
   *
   * The default implementation of this interface methods returns {@code -1}.
   *
   * @return number of objects currently in use
   */
  default int getInUse() {
    return -1;
  }
}
