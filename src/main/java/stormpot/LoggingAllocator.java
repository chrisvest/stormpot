/*
 * Copyright © Chris Vest (mr.chrisvest@gmail.com)
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

import java.util.concurrent.CompletionStage;

/**
 * An abstract wrapper for {@link Allocator} and {@link Reallocator} instances, that adds a logging callback
 * when allocator operations fail.
 * @param <T> The concrete objects being allocated.
 */
public abstract class LoggingAllocator<T extends Poolable> implements Reallocator<T> {
  /**
   * Indicates an exception was thrown by {@link Reallocator#reallocate(Slot, Poolable)}.
   */
  public static final String REALLOCATION_FAILED = "Reallocation failed";
  /**
   * Indicates the {@link CompletionStage} returned by {@link Reallocator#reallocateAsync(Slot, Poolable)} failed.
   */
  public static final String ASYNC_REALLOCATION_FAILED = "Asynchronous reallocation failed";
  /**
   * Indicates the {@link CompletionStage} returned by {@link Reallocator#reallocateAsync(Slot, Poolable)}
   * was {@code null}.
   */
  public static final String ASYNC_REALLOCATION_RETURNED_NULL =
          "Asynchronous reallocation returned null CompletionStage";
  /**
   * Indicates an exception was thrown by {@link Allocator#allocate(Slot)}.
   */
  public static final String ALLOCATION_FAILED = "Allocation failed";
  /**
   * Indicates the {@link CompletionStage} returned by {@link Allocator#allocateAsync(Slot)} failed.
   */
  public static final String ASYNC_ALLOCATION_FAILED = "Asynchronous allocation failed";
  /**
   * Indicates the {@link CompletionStage} returned by {@link Allocator#allocateAsync(Slot)} was {@code null}.
   */
  public static final String ASYNC_ALLOCATION_RETURNED_NULL = "Asynchronous allocation returned null CompletionStage";
  /**
   * Indicates an exception was thrown by {@link Allocator#deallocate(Poolable)}.
   */
  public static final String DEALLOCATION_FAILED = "Deallocation failed";
  /**
   * Indicates the {@link CompletionStage} returned by {@link Allocator#deallocateAsync(Poolable)} failed.
   */
  public static final String ASYNC_DEALLOCATION_FAILED = "Asynchronous deallocation failed";
  /**
   * Indicates the {@link CompletionStage} returned by {@link Allocator#deallocateAsync(Poolable)} was {@code null}.
   */
  public static final String ASYNC_DEALLOCATION_RETURNED_NULL =
          "Asynchronous deallocation returned null CompletionStage";

  private final Allocator<T> allocator;
  private final Reallocator<T> reallocator;

  /**
   * Constructs a LoggingAllocator by wrapping the provided {@code Allocator}.
   * If the given {@code Allocator} is an instance of {@code Reallocator}, then the reallocator methods are
   * delegated directly, otherwise they are re-implemented in terms of the {@link Allocator} API.
   *
   * @param allocator The allocator to wrap. It must not be {@code null}.
   *                  This allocator will be used for allocation, and optionally
   *                  for reallocation if it implements the {@code Reallocator} interface.
   */
  public LoggingAllocator(Allocator<T> allocator) {
    if (allocator instanceof Reallocator) {
      this.reallocator = (Reallocator<T>) allocator;
    } else {
      reallocator = null;
    }
    this.allocator = allocator;
  }

  @Override
  public T reallocate(Slot slot, T poolable) throws Exception {
    try {
      if (reallocator != null) {
        return reallocator.reallocate(slot, poolable);
      } else {
        allocator.deallocate(poolable);
        return allocator.allocate(slot);
      }
    } catch (Exception e) {
      logMessage(REALLOCATION_FAILED, e);
      throw e;
    }
  }

  @Override
  public CompletionStage<T> reallocateAsync(Slot slot, T poolable) {
    final CompletionStage<T> stage;
    if (reallocator != null) {
      stage = reallocator.reallocateAsync(slot, poolable);
    } else {
      stage = Reallocator.super.reallocateAsync(slot, poolable);
    }
    if (stage != null) {
      stage.whenComplete((obj, throwable) -> {
        if (throwable != null) {
          logMessage(ASYNC_REALLOCATION_FAILED, throwable);
        }
      });
    } else {
      logMessage(ASYNC_REALLOCATION_RETURNED_NULL, null);
    }
    return stage;
  }

  @Override
  public T allocate(Slot slot) throws Exception {
    try {
      return allocator.allocate(slot);
    } catch (Exception e) {
      logMessage(ALLOCATION_FAILED, e);
      throw e;
    }
  }

  @Override
  public CompletionStage<T> allocateAsync(Slot slot) {
    CompletionStage<T> stage = allocator.allocateAsync(slot);
    if (stage != null) {
      stage.whenComplete((obj, throwable) -> {
        if (throwable != null) {
          logMessage(ASYNC_ALLOCATION_FAILED, throwable);
        }
      });
    } else {
      logMessage(ASYNC_ALLOCATION_RETURNED_NULL, null);
    }
    return stage;
  }

  @Override
  public void deallocate(T poolable) throws Exception {
    try {
      allocator.deallocate(poolable);
    } catch (Exception e) {
      logMessage(DEALLOCATION_FAILED, e);
      throw e;
    }
  }

  @Override
  public CompletionStage<Void> deallocateAsync(T poolable) {
    CompletionStage<Void> stage = allocator.deallocateAsync(poolable);
    if (stage != null) {
      stage.whenComplete((obj, throwable) -> {
        if (throwable != null) {
          logMessage(ASYNC_DEALLOCATION_FAILED, throwable);
        }
      });
    } else {
      logMessage(ASYNC_DEALLOCATION_RETURNED_NULL, null);
    }
    return stage;
  }

  /**
   * Logs a message and an optional associated throwable for diagnostic or debugging purposes.
   * The message will be one of the string constants defined on the {@link LoggingAllocator} class.
   * <p>
   * Subclasses must implement this method and delegate to their preferred logging framework.
   *
   * @param message   The log message to record, never {@code null}.
   * @param throwable The throwable associated with the log message, possibly {@code null}.
   */
  protected abstract void logMessage(String message, Throwable throwable);
}
