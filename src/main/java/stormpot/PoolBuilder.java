/*
 * Copyright © 2011 Chris Vest (mr.chrisvest@gmail.com)
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

import stormpot.internal.PoolBuilderImpl;

import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;

/**
 * The {@code PoolBuilder} collects information about how big a pool should be,
 * and how it should allocate objects, and so on, and finally acts as the
 * factory for building the pool instances themselves, with the
 * {@link #build()} method.
 * <p>
 * Pool builder instances are obtained by calling one of the {@code from*}
 * methods on {@link Pool}, such as {@link Pool#fromThreaded(Allocator)}.
 * <p>
 * This class is made thread-safe by having the fields be protected by the
 * intrinsic object lock on the {@code PoolBuilder} object itself. This way, pools
 * can {@code synchronize} on the builder object to read the values out atomically.
 * <p>
 * The various {@code set*} methods are made to return the {@code PoolBuilder} instance
 * itself, so that the method calls may be chained if so desired.
 * 
 * @author Chris Vest
 * @param <T> The type of {@link Poolable} objects that a {@link Pool} based
 * on this {@code PoolBuilder} will produce.
 */
public sealed interface PoolBuilder<T extends Poolable>
        extends Cloneable
        permits PoolBuilderImpl {
  /**
   * Set the size of the pool we are building.
   * <p>
   * Pools are required to control the allocations and deallocations, such that
   * no more than this number of objects are allocated at any time.
   * <p>
   * This means that a pool of size 1, whose single object have expired, will
   * deallocate that one object before allocating a replacement.
   * <p>
   * The size must be at least zero, or an {@link IllegalArgumentException} will
   * be thrown when building the pool.
   * <p>
   * Note that the pool size can be modified after the pool has been built, by
   * calling the {@link Pool#setTargetSize(long)} or
   * {@link ManagedPool#setTargetSize(long)} methods.
   *
   * @param size The target pool size. Must be at least 0.
   * @return This {@code PoolBuilder} instance.
   */
  PoolBuilder<T> setSize(long size);

  /**
   * Get the currently configured size. The default is 10.
   * @return The configured pool size.
   */
  long getSize();

  /**
   * Set the {@link Allocator} or {@link Reallocator} to use for the pools we
   * want to configure. This will change the type-parameter of the {@code PoolBuilder}
   * object to match that of the new Allocator.
   * <p>
   * The allocator is initially specified by the {@link Pool#from(Allocator)}
   * method, so there is usually no need to set it later.
   *
   * @param allocator The allocator we want our pools to use.
   * This cannot be {@code null}.
   * @param <X> The type of {@link Poolable} that is created by the allocator,
   * and the type of objects that the configured pools will contain.
   * @return This {@code PoolBuilder} instance, but with a generic type parameter that
   * matches that of the allocator.
   */
  <X extends Poolable> PoolBuilder<X> setAllocator(Allocator<X> allocator);

  /**
   * Get the configured {@link Allocator} instance.
   * @return The configured Allocator instance.
   */
  Allocator<T> getAllocator();

  /**
   * Get the configured {@link stormpot.Allocator} instance as a
   * {@link stormpot.Reallocator}. If the configured allocator implements the
   * Reallocator interface, then it is returned directly. Otherwise, the
   * allocator is wrapped in an adaptor.
   * @return A configured or adapted Reallocator.
   */
  Reallocator<T> getReallocator();

  /**
   * Set the {@link Expiration} to use for the pools we want to
   * configure. The Expiration determines when a pooled object is valid
   * for claiming, or when the objects are invalid and should be deallocated.
   * <p>
   * The default Expiration is an
   * {@link Expiration#after(long, long, TimeUnit)} that invalidates the
   * objects after they have been active for somewhere from 8 to 10 minutes.
   *
   * @param expiration The expiration we want our pools to use. Not null.
   * @return This {@code PoolBuilder} instance.
   */
  PoolBuilder<T> setExpiration(Expiration<? super T> expiration);

  /**
   * Get the configured {@link Expiration} instance. The default is a
   * {@link Expiration#after(long, long, TimeUnit)} that expires objects after
   * somewhere from 8 to 10 minutes.
   *
   * @return The configured Expiration.
   */
  Expiration<? super T> getExpiration();

  /**
   * Set the {@link MetricsRecorder} to use for the pools we want to configure.
   * @param metricsRecorder The MetricsRecorder to use, or null if we don't
   *                        want to use any.
   * @return This {@code PoolBuilder} instance.
   */
  PoolBuilder<T> setMetricsRecorder(MetricsRecorder metricsRecorder);

  /**
   * Get the configured {@link MetricsRecorder} instance, or {@code null} if none has
   * been configured.
   * @return The configured MetricsRecorder.
   */
  MetricsRecorder getMetricsRecorder();

  /**
   * Get the {@link ThreadFactory} that has been configured, and will be used to create
   * the background allocation threads for the pools. The default is similar to
   * the {@link java.util.concurrent.Executors#defaultThreadFactory()}, except
   * the string "Stormpot-" is prepended to the thread name.
   * @return The configured thread factory.
   */
  ThreadFactory getThreadFactory();

  /**
   * Set the {@link ThreadFactory} that the pools will use to create its background
   * threads with. The ThreadFactory is not allowed to be {@code null}, and creating
   * a pool with a {@code null} ThreadFactory will throw an {@link IllegalArgumentException}.
   * <p>
   * This setting only affects {@linkplain Pool#fromThreaded(Allocator) threaded} pools.
   *
   * @param factory The ThreadFactory the pool should use to create their
   *                background threads.
   * @return This {@code PoolBuilder} instance.
   */
  PoolBuilder<T> setThreadFactory(ThreadFactory factory);

  /**
   * Return whether precise object leak detection is enabled, which is
   * the case by default.
   *
   * @return {@code true} if precise object leak detection is enabled.
   * @see #setPreciseLeakDetectionEnabled(boolean)
   */
  boolean isPreciseLeakDetectionEnabled();

  /**
   * Enable or disable precise object leak detection. It is enabled by default.
   * Precise object leak detection makes the pool keep an eye on the allocated
   * Poolables, such that it notices if they get garbage collected without
   * first being deallocated. Using the garbage collector for this purpose,
   * means that no false positives (counting objects as leaked, even though
   * they are not) are ever reported.
   *
   * <table>
   * <tr>
   * <th scope="row">NOTE</th>
   * <td>
   * While the pool is able to detect object leaks, it cannot prevent
   * them. All leaks are a sign that there is a bug in the system; most likely
   * a bug in your code, or in the way the pool is used.
   * </td>
   * </tr>
   * </table>
   *
   * Precise object leak detection incurs virtually no overhead, and is safe to
   * leave enabled at all times – even in the most demanding production
   * environments.
   * <p>
   * This setting only affects {@linkplain Pool#fromThreaded(Allocator) threaded} and
   * {@linkplain Pool#fromInline(Allocator) inline} pools.
   *
   * @param enabled {@code true} to turn on precise object leak detection (the
   *                default) {@code false} to turn it off.
   * @return This {@code PoolBuilder} instance.
   */
  PoolBuilder<T> setPreciseLeakDetectionEnabled(boolean enabled);

  /**
   * Return whether background expiration is enabled.
   * By default, background expiration is enabled.
   *
   * @return {@code true} if background expiration is enabled.
   * @see #setBackgroundExpirationEnabled(boolean)
   */
  boolean isBackgroundExpirationEnabled();

  /**
   * Enable or disable background object expiration checking. This is enabled
   * by default, but can be turned off if the check is expensive.
   * The cost of the check matters because it might end up taking resources
   * away from the background thread and hinder its ability to keep up with the
   * demand for allocations and deallocations, even though these tasks always
   * take priority over any expiration checking.
   * <p>
   * This setting only affects {@linkplain Pool#fromThreaded(Allocator) threaded} pools.
   *
   * @param enabled {@code true} (the default) to turn background expiration checking on,
   *               {@code false} to turn it off.
   * @return This {@code PoolBuilder} instance.
   */
  PoolBuilder<T> setBackgroundExpirationEnabled(boolean enabled);

  /**
   * Return the default approximate delay, in milliseconds, between background
   * maintenance tasks, such as the background expiration checks and retrying
   * failed allocations.
   *
   * @return the delay, in milliseconds, between background maintenance tasks.
   */
  int getBackgroundExpirationCheckDelay();

  /**
   * Set the approximate delay, in milliseconds, between background maintenance tasks.
   * These tasks include the background expiration checks, and retrying failed allocations.
   * <p>
   * The default delay is 1.000 milliseconds (1 second). Lowering this value will
   * improve the pools responsiveness to repairing failed allocations, and also increase
   * the frequency of the background expiration checks. This comes at the cost of higher
   * idle CPU usage.
   * <p>
   * It is not recommended to set this value lower than 100 milliseconds. Values lower
   * than this tend to have increased CPU and power usage, for very little gain in
   * responsiveness for the background tasks.
   * <p>
   * This setting only affects {@linkplain Pool#fromThreaded(Allocator) threaded} pools.
   *
   * @param delay the desired delay, in milliseconds, between background maintenance tasks.
   * @return This {@code PoolBuilder} instance.
   */
  PoolBuilder<T> setBackgroundExpirationCheckDelay(int delay);

  /**
   * Return whether Stormpot will prioritize minimizing its memory overhead over
   * maximizing performance.
   * <p>
   * This is {@code true} by default, as the performance gains don't show except in
   * intense and highly concurrent use cases.
   *
   * @return {@code true} for prioritizing memory usage over absolute performance,
   * otherwise {@code false} for prioritizing performance at all costs.
   */
  boolean isOptimizeForReducedMemoryUsage();

  /**
   * Tell the pool to optimize for either low memory usage (when giving {@code true}),
   * or maximal performance (when giving {@code false}).
   * <p>
   * This is {@code true} by default, and should only be set to {@code false} when the
   * pool is expected to be "relatively small" and will experience an extremely high
   * level of multithreaded access.
   *
   * @param reduceMemoryUsage whether to prioritize memory usage or performance.
   * @return This {@code PoolBuilder} instance.
   */
  PoolBuilder<T> setOptimizeForReducedMemoryUsage(boolean reduceMemoryUsage);

  /**
   * Return the maximum number of object allocations and deallocations that Stormpot
   * will perform concurrently.
   * <p>
   * The default is 1, so only one object at a time can be allocated or deallocated.
   *
   * @return The maximum number of concurrent allocations and deallocations.
   */
  int getMaxConcurrentAllocations();

  /**
   * Set the maximum number of concurrent allocations or deallocations.
   * <p>
   * If object allocation is particularly slow, it can make sense to allow Stormpot to
   * perform allocations in parallel, so that the pool can fill up faster when its newly
   * created, or recover faster if a large number of objects need to be replaced.
   * <p>
   * This setting only affects {@linkplain Pool#fromThreaded(Allocator) threaded} pools.
   *
   * @return This {@code PoolBuilder} instance.
   */
  PoolBuilder<T> setMaxConcurrentAllocations(int allocationConcurrency);

  /**
   * Returns a shallow copy of this {@code PoolBuilder} object.
   * @return A new {@code PoolBuilder} object of the exact same type as this one, with
   * identical values in all its fields.
   */
  PoolBuilder<T> clone();

  /**
   * Build a {@link Pool} instance based on the collected configuration.
   * @return A {@link Pool} instance as configured by this builder.
   */
  Pool<T> build();
}
