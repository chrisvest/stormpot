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

final class TimingReallocatorAdaptor<T extends Poolable>
    extends TimingReallocatingAdaptor<T>
    implements Reallocator<T> {
  public TimingReallocatorAdaptor(
      Reallocator<T> allocator, MetricsRecorder metricsRecorder) {
    super(allocator, metricsRecorder);
  }

  @Override
  public T reallocate(Slot slot, T poolable) throws Exception {
    long start = System.currentTimeMillis();
    try {
      T obj = ((Reallocator<T>) allocator).reallocate(slot, poolable);
      long elapsed = System.currentTimeMillis() - start;
      metricsRecorder.recordReallocationLatencySampleMillis(elapsed);
      return obj;
    } catch (Exception e) {
      long elapsed = System.currentTimeMillis() - start;
      metricsRecorder.recordReallocationFailureLatencySampleMillis(elapsed);
      throw e;
    }
  }
}
