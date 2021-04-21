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

import java.util.Map;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;

import static java.util.Objects.requireNonNull;
import static java.util.concurrent.TimeUnit.MINUTES;
import static stormpot.AllocationProcessMode.DIRECT;
import static stormpot.AllocationProcessMode.INLINE;
import static stormpot.AllocationProcessMode.THREADED;
import static stormpot.Expiration.after;
import static stormpot.Expiration.never;
import static stormpot.StormpotThreadFactory.INSTANCE;

/**
 * The `PoolBuilder` collects information about how big a pool should be,
 * and how it should allocate objects, an so on, and finally acts as the
 * factory for building the pool instances themselves, with the
 * {@link #build()} method.
 *
 * Pool builder instances are obtained by calling one of the `from*`
 * methods on {@link Pool}, such as {@link Pool#fromThreaded(Allocator)}.
 *
 * This class is made thread-safe by having the fields be protected by the
 * intrinsic object lock on the `PoolBuilder` object itself. This way, pools
 * can `synchronize` on the builder object to read the values out atomically.
 *
 * The various set* methods are made to return the `PoolBuilder` instance
 * itself, so that the method calls may be chained if so desired.
 * 
 * @author Chris Vest <mr.chrisvest@gmail.com>
 * @param <T> The type of {@link Poolable} objects that a {@link Pool} based
 * on this `PoolBuilder` will produce.
 */
public final class PoolBuilder<T extends Poolable> implements Cloneable {
  static final Map<AllocationProcessMode, PoolBuilderDefaults> DEFAULTS = Map.of(
      THREADED, new PoolBuilderDefaults(after(8, 10, MINUTES), INSTANCE, true, true, 1000),
      INLINE, new PoolBuilderDefaults(after(8, 10, MINUTES), INSTANCE, true, false, 0),
      DIRECT, new PoolBuilderDefaults(never(), INSTANCE, false, false, 0)
  );

  static final Map<AllocationProcessMode, PoolBuilderPermissions> PERMISSIONS = Map.of(
      THREADED, new PoolBuilderPermissions(true, true, true, true, true),
      INLINE, new PoolBuilderPermissions(true, true, true, false, false),
      DIRECT, new PoolBuilderPermissions(false, true, false, false, false)
  );

  private final AllocationProcess allocationProcess;
  private final PoolBuilderPermissions permissions;
  private Allocator<T> allocator;
  private int size = 10;
  private Expiration<? super T> expiration;
  private MetricsRecorder metricsRecorder;
  private ThreadFactory threadFactory;
  private boolean preciseLeakDetectionEnabled;
  private boolean backgroundExpirationEnabled;
  private int backgroundExpirationCheckDelay;

  /**
   * Build a new empty `PoolBuilder` object.
   */
  PoolBuilder(AllocationProcess allocationProcess, Allocator<T> allocator) {
    requireNonNull(allocator, "The Allocator cannot be null.");
    requireNonNull(allocationProcess, "The AllocationProcess cannot be null.");
    this.allocator = allocator;
    this.allocationProcess = allocationProcess;
    this.permissions = PERMISSIONS.get(allocationProcess.getMode());
    PoolBuilderDefaults defaults = DEFAULTS.get(allocationProcess.getMode());
    this.expiration = defaults.expiration;
    this.threadFactory = defaults.threadFactory;
    this.preciseLeakDetectionEnabled = defaults.preciseLeakDetectionEnabled;
    this.backgroundExpirationEnabled = defaults.backgroundExpirationEnabled;
    this.backgroundExpirationCheckDelay = defaults.backgroundExpirationCheckDelay;
  }

  /**
   * Set the size of the pool we are building.
   *
   * Pools are required to control the allocations and deallocations, such that
   * no more than this number of objects are allocated at any time.
   *
   * This means that a pool of size 1, whose single object have expired, will
   * deallocate that one object before allocating a replacement.
   *
   * The size must be at least one, or an {@link IllegalArgumentException} will
   * be thrown when building the pool.
   *
   * Note that the pool size can be modified after the pool has been built, by
   * calling the {@link Pool#setTargetSize(int)} or
   * {@link ManagedPool#setTargetSize(int)} methods.
   *
   * @param size The target pool size. Must be at least 0.
   * @return This `PoolBuilder` instance.
   */
  public synchronized PoolBuilder<T> setSize(int size) {
    checkPermission(permissions.setSize, "size");
    if (size < 0) {
      throw new IllegalArgumentException("Size must be at least 0, but was " + size + ".");
    }
    this.size = size;
    return this;
  }

  /**
   * Get the currently configured size. The default is 10.
   * @return The configured pool size.
   */
  public synchronized int getSize() {
    return size;
  }

  /**
   * Set the {@link Allocator} or {@link Reallocator} to use for the pools we
   * want to configure. This will change the type-parameter of the `PoolBuilder`
   * object to match that of the new Allocator.
   *
   * The allocator is initially specified by the {@link Pool#from(Allocator)}
   * method, so there is usually no need to set it later.
   *
   * @param allocator The allocator we want our pools to use.
   * This cannot be `null`.
   * @param <X> The type of {@link Poolable} that is created by the allocator,
   * and the type of objects that the configured pools will contain.
   * @return This `PoolBuilder` instance, but with a generic type parameter that
   * matches that of the allocator.
   */
  @SuppressWarnings("unchecked")
  public synchronized <X extends Poolable> PoolBuilder<X> setAllocator(
      Allocator<X> allocator) {
    checkPermission(permissions.setAllocator, "allocator");
    requireNonNull(allocator, "The Allocator cannot be null.");
    this.allocator = (Allocator<T>) allocator;
    return (PoolBuilder<X>) this;
  }

  /**
   * Get the configured {@link Allocator} instance.
   * @return The configured Allocator instance.
   */
  public synchronized Allocator<T> getAllocator() {
    return allocator;
  }

  /**
   * Get the configured {@link stormpot.Allocator} instance as a
   * {@link stormpot.Reallocator}. If the configured allocator implements the
   * Reallocator interface, then it is returned directly. Otherwise, the
   * allocator is wrapped in an adaptor.
   * @return A configured or adapted Reallocator.
   */
  public synchronized Reallocator<T> getReallocator() {
    if (allocator instanceof Reallocator) {
      return (Reallocator<T>) allocator;
    }
    return new ReallocatingAdaptor<>(allocator);
  }

  /**
   * Set the {@link Expiration} to use for the pools we want to
   * configure. The Expiration determines when a pooled object is valid
   * for claiming, or when the objects are invalid and should be deallocated.
   *
   * The default Expiration is an
   * {@link Expiration#after(long, long, TimeUnit)} that invalidates the
   * objects after they have been active for somewhere between 8 to 10 minutes.
   *
   * @param expiration The expiration we want our pools to use. Not null.
   * @return This `PoolBuilder` instance.
   */
  public synchronized PoolBuilder<T> setExpiration(Expiration<? super T> expiration) {
    checkPermission(permissions.setExpiration, "expiration");
    requireNonNull(expiration, "Expiration cannot be null.");
    this.expiration = expiration;
    return this;
  }

  /**
   * Get the configured {@link Expiration} instance. The default is a
   * {@link Expiration#after(long, long, TimeUnit)} that expires objects after
   * somewhere between 8 to 10 minutes.
   *
   * @return The configured Expiration.
   */
  public synchronized Expiration<? super T> getExpiration() {
    return expiration;
  }

  /**
   * Set the {@link MetricsRecorder} to use for the pools we want to configure.
   * @param metricsRecorder The MetricsRecorder to use, or null if we don't
   *                        want to use any.
   * @return This `PoolBuilder` instance.
   */
  public synchronized PoolBuilder<T> setMetricsRecorder(MetricsRecorder metricsRecorder) {
    this.metricsRecorder = metricsRecorder;
    return this;
  }

  /**
   * Get the configured {@link MetricsRecorder} instance, or null if none has
   * been configured.
   * @return The configured MetricsRecorder.
   */
  public synchronized MetricsRecorder getMetricsRecorder() {
    return metricsRecorder;
  }

  /**
   * Get the ThreadFactory that has been configured, and will be used to create
   * the background allocation threads for the pools. The default is similar to
   * the {@link java.util.concurrent.Executors#defaultThreadFactory()}, except
   * the string "Stormpot-" is prepended to the thread name.
   * @return The configured thread factory.
   */
  public synchronized ThreadFactory getThreadFactory() {
    return threadFactory;
  }

  /**
   * Set the ThreadFactory that the pools will use to create its background
   * threads with. The ThreadFactory is not allowed to be null, and creating
   * a pool with a null ThreadFactory will throw an IllegalArgumentException.
   * @param factory The ThreadFactory the pool should use to create their
   *                background threads.
   * @return This `PoolBuilder` instance.
   */
  public synchronized PoolBuilder<T> setThreadFactory(ThreadFactory factory) {
    checkPermission(permissions.setThreadFactory, "thread factory");
    requireNonNull(factory, "ThreadFactory cannot be null.");
    threadFactory = factory;
    return this;
  }

  /**
   * Return whether or not precise object leak detection is enabled, which is
   * the case by default.
   * @return `true` if precise object leak detection is enabled.
   * @see #setPreciseLeakDetectionEnabled(boolean)
   */
  public synchronized boolean isPreciseLeakDetectionEnabled() {
    return preciseLeakDetectionEnabled;
  }

  /**
   * Enable or disable precise object leak detection. It is enabled by default.
   * Precise object leak detection makes the pool keep an eye on the allocated
   * Poolables, such that it notices if they get garbage collected without
   * first being deallocated. Using the garbage collector for this purpose,
   * means that no false positives (counting objects as leaked, even though
   * they are not) are ever reported.
   *
   * [NOTE]
   * --
   * While the pool is able to detect object leaks, it cannot prevent
   * them. All leaks are a sign that there is a bug in the system; most likely
   * a bug in your code, or in the way the pool is used.
   * --
   *
   * Precise object leak detection incurs virtually no overhead, and is safe to
   * leave enabled at all times – even in the most demanding production
   * environments.
   *
   * @param enabled `true` to turn on precise object leak detection (the
   *                default) `false` to turn it off.
   * @return This `PoolBuilder` instance.
   */
  public synchronized PoolBuilder<T> setPreciseLeakDetectionEnabled(boolean enabled) {
    this.preciseLeakDetectionEnabled = enabled;
    return this;
  }

  /**
   * Return whether or not background expiration is enabled.
   * By default, background expiration is enabled.
   *
   * @return `true` if background expiration is enabled.
   * @see #setBackgroundExpirationEnabled(boolean)
   */
  public synchronized boolean isBackgroundExpirationEnabled() {
    return backgroundExpirationEnabled;
  }

  /**
   * Enable or disable background object expiration checking. This is enabled
   * by default, but can be turned off if the check is expensive.
   * The cost of the check matters because it might end up taking resources
   * away from the background thread and hinder its ability to keep up with the
   * demand for allocations and deallocations, even though these tasks always
   * take priority over any expiration checking.
   *
   * @param enabled `true` (the default) to turn background expiration checking on,
   *               `false` to turn it off.
   * @return This `PoolBuilder` instance.
   */
  public synchronized PoolBuilder<T> setBackgroundExpirationEnabled(boolean enabled) {
    checkPermission(permissions.setBackgroundExpiration, "background expiration enabled/disabled");
    backgroundExpirationEnabled = enabled;
    return this;
  }

  /**
   * Return the default approximate delay, in milliseconds, between background
   * maintenance tasks, such as the background expiration checks and retrying
   * failed allocations.
   *
   * @return the delay, in milliseconds, between background maintenance tasks.
   */
  public synchronized int getBackgroundExpirationCheckDelay() {
    return backgroundExpirationCheckDelay;
  }

  /**
   * Set the approximate delay, in milliseconds, between background maintenance tasks.
   * These tasks include the background expiration checks, and retrying failed allocations.
   *
   * The default delay is 1.000 milliseconds (1 second). Lowering this value will
   * improve the pools responsiveness to repairing failed allocations, and also increase
   * the frequency of the background expiration checks. This comes at the cost of higher
   * idle CPU usage.
   *
   * It is not recommended to set this value lower than 100 milliseconds. Values lower
   * than this tend to have increased CPU and power usage, for very little gain in
   * responsiveness for the background tasks.
   *
   * @param delay the desired delay, in milliseconds, between background maintenance tasks.
   * @return This `PoolBuilder` instance.
   */
  public synchronized PoolBuilder<T> setBackgroundExpirationCheckDelay(int delay) {
    checkPermission(permissions.setBackgroundExpiration, "background expiration check delay");
    if (delay < 0) {
      throw new IllegalArgumentException("Background expiration check delay cannot be negative.");
    }
    backgroundExpirationCheckDelay = delay;
    return this;
  }

  /**
   * Returns a shallow copy of this `PoolBuilder` object.
   * @return A new `PoolBuilder` object of the exact same type as this one, with
   * identical values in all its fields.
   */
  @SuppressWarnings("unchecked")
  @Override
  public final synchronized PoolBuilder<T> clone() {
    try {
      return (PoolBuilder<T>) super.clone();
    } catch (CloneNotSupportedException e) {
      throw new AssertionError(e);
    }
  }

  /**
   * Build a {@link Pool} instance based on the collected configuration.
   * @return A {@link Pool} instance as configured by this builder.
   */
  public synchronized Pool<T> build() {
    return new BlazePool<>(this, allocationProcess);
  }

  /**
   * Returns a `Reallocator`, possibly by adapting the configured
   * `Allocator` if need be.
   * If a `MetricsRecorder` has been configured, the return `Reallocator` will
   * automatically record allocation, reallocation and deallocation latencies.
   */
  synchronized Reallocator<T> getAdaptedReallocator() {
    if (metricsRecorder == null) {
      if (allocator instanceof Reallocator) {
        return (Reallocator<T>) allocator;
      }
      return new ReallocatingAdaptor<>(allocator);
    } else {
      if (allocator instanceof Reallocator) {
        return new TimingReallocatorAdaptor<>(
            (Reallocator<T>) allocator, metricsRecorder);
      }
      return new TimingReallocatingAdaptor<>(allocator, metricsRecorder);
    }
  }

  private void checkPermission(boolean permission, String fieldDescription) {
    if (!permission) {
      String message = "The " + allocationProcess.getMode() + " allocation process does " +
          "not support configuring the " + fieldDescription + ".";
      throw new IllegalStateException(message);
    }
  }
}
