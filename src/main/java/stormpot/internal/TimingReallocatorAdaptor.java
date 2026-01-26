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

import stormpot.MetricsRecorder;
import stormpot.Poolable;
import stormpot.Reallocator;
import stormpot.Slot;

import java.util.concurrent.CompletionStage;

final class TimingReallocatorAdaptor<T extends Poolable> extends TimingReallocatingAdaptor<T>
    implements Reallocator<T> {
  TimingReallocatorAdaptor(
      Reallocator<T> allocator, MetricsRecorder metricsRecorder) {
    super(allocator, metricsRecorder);
  }

  @Override
  public T reallocate(Slot slot, T poolable) throws Exception {
    long start = start();
    try {
      T obj = ((Reallocator<T>) allocator).reallocate(slot, poolable);
      metricsRecorder.recordReallocationLatencySampleMillis(elapsedMillis(start));
      return obj;
    } catch (Exception e) {
      metricsRecorder.recordReallocationFailureLatencySampleMillis(elapsedMillis(start));
      throw e;
    }
  }

  @Override
  public CompletionStage<T> reallocateAsync(Slot slot, T poolable) {
    final long start = start();
    CompletionStage<T> stage = ((Reallocator<T>) allocator).reallocateAsync(slot, poolable);
    if (stage == null) {
      metricsRecorder.recordReallocationFailureLatencySampleMillis(elapsedMillis(start));
      return null;
    }
    return whenCompleteFirst(stage, (obj, e) -> {
      if (obj == null) {
        metricsRecorder.recordReallocationFailureLatencySampleMillis(elapsedMillis(start));
      } else {
        metricsRecorder.recordReallocationLatencySampleMillis(elapsedMillis(start));
      }
    });
  }
}
