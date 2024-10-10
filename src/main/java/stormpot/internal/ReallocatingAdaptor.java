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
import stormpot.MetricsRecorder;
import stormpot.Poolable;
import stormpot.Reallocator;
import stormpot.Slot;

import java.util.Objects;

/**
 * An adaptor that implements {@link Reallocator} in terms of a given {@link Allocator}.
 * @param <T> The concrete poolable type.
 */
public class ReallocatingAdaptor<T extends Poolable> implements Reallocator<T> {
  final Allocator<T> allocator;

  /**
   * Adapt the given {@link Allocator} into a {@link Reallocator}.
   * @param allocator The allocator to adapt.
   */
  public ReallocatingAdaptor(Allocator<T> allocator) {
    this.allocator = Objects.requireNonNull(allocator, "allocator");
  }

  static <T extends Poolable> Reallocator<T> adapt(
          Allocator<T> allocator, MetricsRecorder metricsRecorder) {
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

  @Override
  public T reallocate(Slot slot, T poolable) throws Exception {
    try {
      allocator.deallocate(poolable);
    } catch (Throwable ignore) { // NOPMD
      // ignored as per specification
    }
    return allocator.allocate(slot);
  }

  @Override
  public T allocate(Slot slot) throws Exception {
    return allocator.allocate(slot);
  }

  @Override
  public void deallocate(T poolable) throws Exception {
    allocator.deallocate(poolable);
  }

  /**
   * Unwrap this adaptor to reveal the underlying {@link Allocator}.
   * @return The allocator adapted by this reallocator.
   */
  public Allocator<T> unwrap() {
    return allocator;
  }
}
