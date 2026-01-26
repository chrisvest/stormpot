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
package stormpot.internal;

import stormpot.Allocator;
import stormpot.MetricsRecorder;
import stormpot.Poolable;
import stormpot.Reallocator;
import stormpot.Slot;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.TimeUnit;
import java.util.function.BiConsumer;

class TimingReallocatingAdaptor<T extends Poolable>
    extends ReallocatingAdaptor<T>
    implements Reallocator<T> {

  final MetricsRecorder metricsRecorder;

  TimingReallocatingAdaptor(
      Allocator<T> allocator,
      MetricsRecorder metricsRecorder) {
    super(allocator);
    this.metricsRecorder = metricsRecorder;
  }

  @Override
  public T allocate(Slot slot) throws Exception {
    long start = start();
    try {
      T obj = super.allocate(slot);
      metricsRecorder.recordAllocationLatencySampleMillis(elapsedMillis(start));
      return obj;
    } catch (Exception e) {
      metricsRecorder.recordAllocationFailureLatencySampleMillis(elapsedMillis(start));
      throw e;
    }
  }

  @Override
  public CompletionStage<T> allocateAsync(Slot slot) {
    final long start = start();
    CompletionStage<T> stage = allocator.allocateAsync(slot);
    if (stage == null) {
      metricsRecorder.recordAllocationFailureLatencySampleMillis(elapsedMillis(start));
      return null;
    }
    return whenCompleteFirst(stage, (obj, e) -> {
      if (obj == null) {
        metricsRecorder.recordAllocationFailureLatencySampleMillis(elapsedMillis(start));
      } else {
        metricsRecorder.recordAllocationLatencySampleMillis(elapsedMillis(start));
      }
    });
  }

  @Override
  public void deallocate(T poolable) throws Exception {
    long start = start();
    try {
      super.deallocate(poolable);
    } finally {
      metricsRecorder.recordDeallocationLatencySampleMillis(elapsedMillis(start));
    }
  }

  @Override
  public CompletionStage<Void> deallocateAsync(T poolable) {
    final long start = start();
    CompletionStage<Void> stage = allocator.deallocateAsync(poolable);
    if (stage == null) {
      metricsRecorder.recordDeallocationLatencySampleMillis(elapsedMillis(start));
      return null;
    }
    return whenCompleteFirst(stage,
            (obj, e) -> metricsRecorder.recordDeallocationLatencySampleMillis(elapsedMillis(start)));
  }

  static long start() {
    return System.nanoTime();
  }

  static long elapsedMillis(long start) {
    long elapsedNanos = System.nanoTime() - start;
    return TimeUnit.NANOSECONDS.toMillis(elapsedNanos);
  }

  /**
   * Attach the given {@code whenComplete} callback to the given {@code stage}, such that it runs before any other
   * callbacks that are later attached to the returned stage.
   * @param stage The stage to attach the callback to.
   * @param action The callback to run.
   * @return The stage that will run subsequently attached callbacks after the given callback.
   * @param <T> The result type of the stage.
   */
  static <T> CompletionStage<T> whenCompleteFirst(CompletionStage<T> stage, BiConsumer<? super T, Throwable> action) {
    CompletableFuture<T> forward = new CompletableFuture<>();
    stage.whenComplete((obj, e) -> {
      action.accept(obj, e);
      if (e == null) {
        forward.complete(obj);
      } else {
        forward.completeExceptionally(e);
      }
    });
    return forward;
  }
}
