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

import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;

/**
 * = The basic configuration "bean" class.
 *
 * Instances of this class is passed to the constructors of {@link Pool pools}
 * so that they know how big they should be, how to allocate objects and when
 * to deallocate objects.
 *
 * This class is made thread-safe by having the fields be protected by the
 * intrinsic object lock on the Config object itself. This way, pools can
 * `synchronize` on the config object to read the values out
 * atomically.
 *
 * The various set* methods are made to return the Config instance itself, so
 * that the method calls may be chained if so desired.
 * 
 * = Standardised configuration
 *
 * The contract of the Config class, and how Pools will interpret it, is within
 * the context of a so-called standardised configuration. All pool and Config
 * implementations must behave similarly in a standardised configuration.
 *
 * It is conceivable that some pool implementations will come with their own
 * sub-classes of Config, that allow greater control over the pools behaviour.
 * It is even permissible that these pool implementations may
 * deviate from the contract of the Pool interface. However, they are only
 * allowed to do so in a non-standard configuration. That is, any deviation
 * from the specified contracts must be explicitly enabled.
 * 
 * @author Chris Vest <mr.chrisvest@gmail.com>
 * @param <T> The type of {@link Poolable} objects that a {@link Pool} based
 * on this Config will produce.
 */
@SuppressWarnings("unchecked")
public class Config<T extends Poolable> implements Cloneable {

  private int size = 10;
  private Expiration<? super T> expiration =
      new TimeSpreadExpiration<T>(480000, 600000, TimeUnit.MILLISECONDS); // 8 to 10 minutes
  private Allocator<?> allocator;
  private MetricsRecorder metricsRecorder;
  private ThreadFactory threadFactory = StormpotThreadFactory.INSTANCE;
  private boolean preciseLeakDetectionEnabled = true;
  private boolean backgroundExpirationEnabled = false;

  /**
   * Build a new empty Config object. Most settings have reasonable default
   * values. However, no {@link Allocator} is configured by default, and one
   * must make sure to set one.
   */
  public Config() {
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
   * @return This Config instance.
   */
  public synchronized Config<T> setSize(int size) {
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
   * want to configure. This will change the type-parameter of the Config
   * object to match that of the new Allocator.
   *
   * The allocator is initially `null` in a new Config object, and can be set
   * to `null` any time. However, in a standard configuration, it must be
   * non-null when the Config is passed to a Pool constructor. Otherwise the
   * constructor will throw an {@link IllegalArgumentException}.
   * @param allocator The allocator we want our pools to use.
   * @param <X> The type of {@link Poolable} that is created by the allocator,
   * and the type of objects that the configured pools will contain.
   * @return This Config instance, but with a generic type parameter that
   * matches that of the allocator.
   */
  public synchronized <X extends Poolable> Config<X> setAllocator(
      Allocator<X> allocator) {
    this.allocator = allocator;
    return (Config<X>) this;
  }

  /**
   * Get the configured {@link Allocator} instance. There is no configured
   * allocator by default, so this must be {@link #setAllocator(Allocator) set}
   * before instantiating any Pool implementations from this Config.
   * @return The configured Allocator instance, if any.
   */
  public synchronized Allocator<T> getAllocator() {
    return (Allocator<T>) allocator;
  }

  /**
   * Get the configured {@link stormpot.Allocator} instance as a
   * {@link stormpot.Reallocator}. If the configured allocator implements the
   * Reallocator interface, then it is returned directly. Otherwise, the
   * allocator is wrapped in an adaptor.
   * @return A configured or adapted Reallocator, if any.
   */
  public synchronized Reallocator<T> getReallocator() {
    if (allocator == null) {
      return null;
    }
    if (allocator instanceof Reallocator) {
      return (Reallocator<T>) allocator;
    }
    return new ReallocatingAdaptor<T>((Allocator<T>) allocator);
  }

  /**
   * Set the {@link Expiration} to use for the pools we want to
   * configure. The Expiration determines when a pooled object is valid
   * for claiming, or when the objects are invalid and should be deallocated.
   *
   * The default Expiration is a {@link TimeSpreadExpiration} that
   * invalidates the objects after they have been active for somewhere between
   * 8 to 10 minutes.
   * @param expiration The expiration we want our pools to use. Not null.
   * @return This Config instance.
   */
  public synchronized Config<T> setExpiration(Expiration<? super T> expiration) {
    this.expiration = expiration;
    return this;
  }

  /**
   * Get the configured {@link Expiration} instance. The default is a
   * {@link TimeSpreadExpiration} that expires objects after somewhere between
   * 8 to 10 minutes.
   * @return The configured Expiration.
   */
  public synchronized Expiration<? super T> getExpiration() {
    return expiration;
  }

  /**
   * Set the {@link MetricsRecorder} to use for the pools we want to configure.
   * @param metricsRecorder The MetricsRecorder to use, or null if we don't
   *                        want to use any.
   * @return This Config instance.
   */
  public synchronized Config<T> setMetricsRecorder(MetricsRecorder metricsRecorder) {
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
   * @return This Config instance.
   */
  public synchronized Config<T> setThreadFactory(ThreadFactory factory) {
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
   * @return This Config instance.
   */
  public synchronized Config<T> setPreciseLeakDetectionEnabled(boolean enabled) {
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
   * Enable or disable background object expiration checking. This is disabled
   * by default, because the pool does not know how expensive this check is.
   * The cost of the check matters because it might end up taking resources
   * away from the background thread and hinder its ability to keep up with the
   * demand for allocations and deallocations, even though these tasks always
   * take priority over any expiration checking.
   *
   * @param enabled `true` to turn background expiration checking on, `false`
   *                (the default) to turn it off.
   * @return This Config instance.
   */
  public synchronized Config<T> setBackgroundExpirationEnabled(boolean enabled) {
    backgroundExpirationEnabled = enabled;
    return this;
  }

  /**
   * Returns a shallow copy of this Config object.
   * @return A new Config object of the exact same type as this one, with
   * identical values in all its fields.
   */
  @Override
  public final synchronized Config<T> clone() {
    try {
      return (Config<T>) super.clone();
    } catch (CloneNotSupportedException e) {
      throw new AssertionError(e);
    }
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
    if (allocator == null) {
      throw new IllegalArgumentException("Allocator cannot be null");
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
  Reallocator<T> getAdaptedReallocator() {
    if (allocator == null) {
      return null;
    }
    if (metricsRecorder == null) {
      if (allocator instanceof Reallocator) {
        return (Reallocator<T>) allocator;
      }
      return new ReallocatingAdaptor<T>((Allocator<T>) allocator);
    } else {
      if (allocator instanceof Reallocator) {
        return new TimingReallocatorAdaptor<T>(
            (Reallocator<T>) allocator, metricsRecorder);
      }
      return new TimingReallocatingAdaptor<T>(
          (Allocator<T>) allocator, metricsRecorder);
    }
  }
}
