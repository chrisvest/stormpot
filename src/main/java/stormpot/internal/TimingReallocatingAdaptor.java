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

import java.util.concurrent.CompletionStage;
import java.util.concurrent.TimeUnit;

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
    stage.whenComplete((obj, e) -> {
      if (obj == null) {
        metricsRecorder.recordAllocationFailureLatencySampleMillis(elapsedMillis(start));
      } else {
        metricsRecorder.recordAllocationLatencySampleMillis(elapsedMillis(start));
      }
    });
    return stage;
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
    stage.whenComplete(
            (obj, e) -> metricsRecorder.recordDeallocationLatencySampleMillis(elapsedMillis(start)));
    return stage;
  }

  static long start() {
    return System.nanoTime();
  }

  static long elapsedMillis(long start) {
    long elapsedNanos = System.nanoTime() - start;
    return TimeUnit.NANOSECONDS.toMillis(elapsedNanos);
  }
}
