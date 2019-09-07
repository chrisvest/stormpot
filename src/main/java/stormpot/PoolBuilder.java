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

import java.util.Objects;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;

import static java.util.concurrent.TimeUnit.MINUTES;

/**
 * == The basic configuration "bean" class.
 *
 * Instances of this class is passed to the constructors of {@link Pool pools}
 * so that they know how big they should be, how to allocate objects and when
 * to deallocate objects.
 *
 * This class is made thread-safe by having the fields be protected by the
 * intrinsic object lock on the `PoolBuilder` object itself. This way, pools
 * can `synchronize` on the config object to read the values out atomically.
 *
 * The various set* methods are made to return the `PoolBuilder` instance
 * itself, so that the method calls may be chained if so desired.
 * 
 * == Standardised configuration
 *
 * The contract of the `PoolBuilder` class, and how Pools will interpret it,
 * is within the context of a so-called standardised configuration.
 * All pool and `PoolBuilder` implementations must behave similarly in a
 * standardised configuration.
 *
 * It is conceivable that some pool implementations will come with their own
 * sub-classes of `PoolBuilder`, that allow greater control over the pools
 * behaviour.
 * It is even permissible that these pool implementations may deviate from the
 * contract of the Pool interface. However, they are only allowed to do so in
 * a non-standard configuration. That is, any deviation from the specified
 * contracts must be explicitly enabled.
 * 
 * @author Chris Vest <mr.chrisvest@gmail.com>
 * @param <T> The type of {@link Poolable} objects that a {@link Pool} based
 * on this `PoolBuilder` will produce.
 */
public final class PoolBuilder<T extends Poolable> implements Cloneable {

  private int size = 10;
  private Expiration<? super T> expiration = Expiration.after(8, 10, MINUTES);
  private Allocator<T> allocator;
  private MetricsRecorder metricsRecorder;
  private ThreadFactory threadFactory = StormpotThreadFactory.INSTANCE;
  private boolean preciseLeakDetectionEnabled = true;
  private boolean backgroundExpirationEnabled = true;

  /**
   * Build a new empty `PoolBuilder` object.
   */
  PoolBuilder(Allocator<T> allocator) {
    requireNonNull(allocator);
    this.allocator = allocator;
  }

  private void requireNonNull(Allocator<?> allocator) {
    Objects.requireNonNull(allocator, "The Allocator cannot be null.");
  }

  /**
   * Set the size of the pools we want to configure them with. Pools are
   * required to control the allocations and deallocations, such that no more
   * than this number of objects are allocated at any time.
   *
   * This means that a pool of size 1, whose single object have expired, will
   * deallocate that one object before allocating a replacement.
   *
   * The size must be at least one for standard pool configurations. A Pool
   * will throw an {@link IllegalArgumentException} from their constructor if
   * this is not the case.
   * @param size The target pool size. Must be at least 1.
   * @return This `PoolBuilder` instance.
   */
  public synchronized PoolBuilder<T> setSize(int size) {
    this.size = size;
    return this;
  }

  /**
   * Get the currently configured size. Default is 10.
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
    requireNonNull(allocator);
    this.allocator = (Allocator<T>) allocator;
    return (PoolBuilder<X>) this;
  }

  /**
   * Get the configured {@link Allocator} instance.
   * @return The configured Allocator instance, if any.
   */
  public synchronized Allocator<T> getAllocator() {
    return allocator;
  }

  /**
   * Get the configured {@link stormpot.Allocator} instance as a
   * {@link stormpot.Reallocator}. If the configured allocator implements the
   * Reallocator interface, then it is returned directly. Otherwise, the
   * allocator is wrapped in an adaptor.
   * @return A configured or adapted Reallocator, if any.
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
   * NOTE: While the pool is able to detect object leaks, it cannot prevent
   * them. All leaks are a sign that there is a bug in the system; most likely
   * a bug in your code, or in the way the pool is used.
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
   * Return whether or not background expiration is enabled, which it is not by
   * default.
   * @return `true` if background expiration has been enabled.
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
    backgroundExpirationEnabled = enabled;
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
    return new BlazePool<>(this);
  }

  /**
   * Check that the configuration is valid in terms of the *standard
   * configuration*. This method is useful in the
   * Pool implementation constructors.
   * @throws IllegalArgumentException If the size is less than one, if the
   * {@link Expiration} is `null`, if the {@link Allocator} is `null`, or if
   * the ThreadFactory is `null`.
   */
  synchronized void validate() throws IllegalArgumentException {
    if (size < 1) {
      throw new IllegalArgumentException(
          "Size must be at least 1, but was " + size);
    }
    if (expiration == null) {
      throw new IllegalArgumentException("Expiration cannot be null");
    }
    if (threadFactory == null) {
      throw new IllegalArgumentException("ThreadFactory cannot be null");
    }
  }

  /**
   * Returns `null` if no allocator has been configured.
   * Otherwise returns a `Reallocator`, possibly by adapting the configured
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
}
