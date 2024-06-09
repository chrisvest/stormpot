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
package stormpot;

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
    long start = System.nanoTime();
    try {
      T obj = super.allocate(slot);
      long elapsedNanos = System.nanoTime() - start;
      long milliseconds = TimeUnit.NANOSECONDS.toMillis(elapsedNanos);
      metricsRecorder.recordAllocationLatencySampleMillis(milliseconds);
      return obj;
    } catch (Exception e) {
      long elapsedNanos = System.nanoTime() - start;
      long milliseconds = TimeUnit.NANOSECONDS.toMillis(elapsedNanos);
      metricsRecorder.recordAllocationFailureLatencySampleMillis(milliseconds);
      throw e;
    }
  }

  @Override
  public void deallocate(T poolable) throws Exception {
    long start = System.nanoTime();
    try {
      super.deallocate(poolable);
    } finally {
      long elapsedNanos = System.nanoTime() - start;
      long milliseconds = TimeUnit.NANOSECONDS.toMillis(elapsedNanos);
      metricsRecorder.recordDeallocationLatencySampleMillis(milliseconds);
    }
  }
}
