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

import stormpot.Allocator;
import stormpot.Expiration;
import stormpot.MetricsRecorder;
import stormpot.Pool;
import stormpot.PoolBuilder;
import stormpot.Poolable;
import stormpot.Reallocator;

import java.util.Map;
import java.util.concurrent.ThreadFactory;

import static java.util.Objects.requireNonNull;
import static java.util.concurrent.TimeUnit.MINUTES;
import static stormpot.Expiration.after;
import static stormpot.Expiration.never;
import static stormpot.internal.AllocationProcessMode.DIRECT;
import static stormpot.internal.AllocationProcessMode.INLINE;
import static stormpot.internal.AllocationProcessMode.THREADED;

/**
 * The {@link PoolBuilder} implementation.
 * @param <T> The concrete poolable type.
 */
public final class PoolBuilderImpl<T extends Poolable> implements PoolBuilder<T> {
  static final ThreadFactory THREAD_FACTORY = Thread.ofVirtual()
          .name("Stormpot-", 1)
          .inheritInheritableThreadLocals(false)
          .factory();

  /**
   * Mapping of {@link AllocationProcessMode} to the pool builder default settings.
   */
  public static final Map<AllocationProcessMode, PoolBuilderDefaults> DEFAULTS = Map.of(
      THREADED, new PoolBuilderDefaults(after(8, 10, MINUTES), THREAD_FACTORY, false, true, 1000, true),
      INLINE, new PoolBuilderDefaults(after(8, 10, MINUTES), THREAD_FACTORY, false, false, 0, true),
      DIRECT, new PoolBuilderDefaults(never(), THREAD_FACTORY, false, false, 0, true)
  );

  /**
   * Mapping of {@link AllocationProcessMode} to the pool builder permissions, deciding what settings can be changed.
   */
  public static final Map<AllocationProcessMode, PoolBuilderPermissions> PERMISSIONS = Map.of(
      THREADED, new PoolBuilderPermissions(true, true, true, true, true),
      INLINE, new PoolBuilderPermissions(true, true, true, false, false),
      DIRECT, new PoolBuilderPermissions(false, true, false, false, false)
  );

  private final AllocationProcess allocationProcess;
  private final PoolBuilderPermissions permissions;
  private Allocator<T> allocator;
  private long size = 10;
  private Expiration<? super T> expiration;
  private MetricsRecorder metricsRecorder;
  private ThreadFactory threadFactory;
  private boolean preciseLeakDetectionEnabled;
  private boolean backgroundExpirationEnabled;
  private int backgroundExpirationCheckDelay;
  private boolean optimizeForMemory;

  /**
   * Build a new empty {@code PoolBuilder} object.
   * @param allocationProcess The allocation process to use.
   * @param allocator The allocator instance to use.
   */
  public PoolBuilderImpl(AllocationProcess allocationProcess, Allocator<T> allocator) {
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
    this.optimizeForMemory = defaults.optimizeForMemory;
  }

  @Override
  public synchronized PoolBuilder<T> setSize(long size) {
    checkPermission(permissions.setSize(), "size");
    if (size < 0) {
      throw new IllegalArgumentException("Size must be at least 0, but was " + size + ".");
    }
    this.size = size;
    return this;
  }

  @Override
  public synchronized long getSize() {
    return size;
  }

  @SuppressWarnings("unchecked")
  @Override
  public synchronized <X extends Poolable> PoolBuilder<X> setAllocator(
      Allocator<X> allocator) {
    checkPermission(permissions.setAllocator(), "allocator");
    requireNonNull(allocator, "The Allocator cannot be null.");
    this.allocator = (Allocator<T>) allocator;
    return (PoolBuilderImpl<X>) this;
  }

  @Override
  public synchronized Allocator<T> getAllocator() {
    return allocator;
  }

  @Override
  public synchronized Reallocator<T> getReallocator() {
    if (allocator instanceof Reallocator) {
      return (Reallocator<T>) allocator;
    }
    return new ReallocatingAdaptor<>(allocator);
  }

  @Override
  public synchronized PoolBuilder<T> setExpiration(Expiration<? super T> expiration) {
    checkPermission(permissions.setExpiration(), "expiration");
    requireNonNull(expiration, "Expiration cannot be null.");
    this.expiration = expiration;
    return this;
  }

  @Override
  public synchronized Expiration<? super T> getExpiration() {
    return expiration;
  }

  @Override
  public synchronized PoolBuilder<T> setMetricsRecorder(MetricsRecorder metricsRecorder) {
    this.metricsRecorder = metricsRecorder;
    return this;
  }

  @Override
  public synchronized MetricsRecorder getMetricsRecorder() {
    return metricsRecorder;
  }

  @Override
  public synchronized ThreadFactory getThreadFactory() {
    return threadFactory;
  }

  @Override
  public synchronized PoolBuilder<T> setThreadFactory(ThreadFactory factory) {
    checkPermission(permissions.setThreadFactory(), "thread factory");
    requireNonNull(factory, "ThreadFactory cannot be null.");
    threadFactory = factory;
    return this;
  }

  @Override
  public synchronized boolean isPreciseLeakDetectionEnabled() {
    return preciseLeakDetectionEnabled;
  }

  @Override
  public synchronized PoolBuilder<T> setPreciseLeakDetectionEnabled(boolean enabled) {
    this.preciseLeakDetectionEnabled = enabled;
    return this;
  }

  @Override
  public synchronized boolean isBackgroundExpirationEnabled() {
    return backgroundExpirationEnabled;
  }

  @Override
  public synchronized PoolBuilder<T> setBackgroundExpirationEnabled(boolean enabled) {
    checkPermission(permissions.setBackgroundExpiration(), "background expiration enabled/disabled");
    backgroundExpirationEnabled = enabled;
    return this;
  }

  @Override
  public synchronized int getBackgroundExpirationCheckDelay() {
    return backgroundExpirationCheckDelay;
  }

  @Override
  public synchronized PoolBuilder<T> setBackgroundExpirationCheckDelay(int delay) {
    checkPermission(permissions.setBackgroundExpiration(), "background expiration check delay");
    if (delay < 0) {
      throw new IllegalArgumentException("Background expiration check delay cannot be negative.");
    }
    backgroundExpirationCheckDelay = delay;
    return this;
  }

  @Override
  public synchronized boolean isOptimizeForReducedMemoryUsage() {
    return optimizeForMemory;
  }

  @Override
  public synchronized PoolBuilder<T> setOptimizeForReducedMemoryUsage(boolean reduceMemoryUsage) {
    this.optimizeForMemory = reduceMemoryUsage;
    return this;
  }

  @SuppressWarnings("unchecked")
  @Override
  public synchronized PoolBuilderImpl<T> clone() {
    try {
      return (PoolBuilderImpl<T>) super.clone();
    } catch (CloneNotSupportedException e) {
      throw new AssertionError(e);
    }
  }

  @Override
  public synchronized Pool<T> build() {
    return new BlazePool<>(this, allocationProcess);
  }

  /**
   * Get a {@link Reallocator} from this pool builder, possibly by adapting the configured {@link Allocator}.
   * @return A {@link Reallocator} instance, either the one given to the pool builder, or a new adapted instance.
   */
  public synchronized Reallocator<T> getAdaptedReallocator() {
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
