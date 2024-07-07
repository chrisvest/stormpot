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
package stormpot.tests;

import org.assertj.core.api.Condition;
import org.junit.jupiter.api.Test;
import stormpot.Allocator;
import stormpot.MetricsRecorder;
import stormpot.Pool;
import stormpot.PoolBuilder;
import stormpot.Reallocator;
import stormpot.Slot;
import stormpot.internal.PoolBuilderImpl;
import stormpot.internal.ReallocatingAdaptor;
import testkits.AlloKit;
import testkits.GenericPoolable;
import testkits.LastSampleMetricsRecorder;
import testkits.NullSlot;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.condition.Not.not;
import static org.junit.jupiter.api.Assertions.fail;
import static testkits.AlloKit.$new;
import static testkits.AlloKit.$throw;
import static testkits.AlloKit.CountingAllocator;
import static testkits.AlloKit.CountingReallocator;
import static testkits.AlloKit.alloc;
import static testkits.AlloKit.allocator;
import static testkits.AlloKit.dealloc;
import static testkits.AlloKit.realloc;
import static testkits.AlloKit.reallocator;

class PoolBuilderReallocatingAdaptorTest {
  private PoolBuilder<GenericPoolable> builder;

  private Reallocator<GenericPoolable> getAdaptedReallocator() {
    return ((PoolBuilderImpl<GenericPoolable>) builder).getAdaptedReallocator();
  }

  @Test
  void mustAdaptAllocatorsToReallocators() {
    Allocator<GenericPoolable> allocator = allocator();
    builder = Pool.from(allocator);
    Reallocator<GenericPoolable> reallocator = builder.getReallocator();
    ReallocatingAdaptor<GenericPoolable> adaptor =
        (ReallocatingAdaptor<GenericPoolable>) reallocator;
    assertThat(adaptor.unwrap()).isSameAs(allocator);
  }

  @Test
  void mustNotReAdaptConfiguredReallocators() {
    Reallocator<GenericPoolable> expected =
        new ReallocatingAdaptor<>(null);
    builder = Pool.from(expected);
    Reallocator<GenericPoolable> actual = builder.getReallocator();
    assertThat(actual).isSameAs(expected);
  }

  @Test
  void getAdaptedReallocatorMustAdaptConfiguredAllocatorIfNoMetricsRecorderConfigured()
      throws Exception {
    AlloKit.CountingAllocator allocator = allocator();
    builder = Pool.from(allocator);
    getAdaptedReallocator().allocate(new NullSlot());
    assertThat(allocator.countAllocations()).isOne();
  }

  @Test
  void getAdaptedReallocatorMustNotAdaptConfiguredReallocatorIfNoMetricsRecorderConfigured()
      throws Exception {
    CountingReallocator reallocator = reallocator();
    builder = Pool.from(reallocator);
    Slot slot = new NullSlot();
    Reallocator<GenericPoolable> adaptedReallocator = getAdaptedReallocator();
    GenericPoolable obj = adaptedReallocator.allocate(slot);
    adaptedReallocator.reallocate(slot, obj);

    assertThat(reallocator.countAllocations()).isOne();
    assertThat(reallocator.countReallocations()).isOne();
  }

  @Test
  void getAdaptedReallocatorMustInstrumentAllocateMethodOnAllocatorIfMetricsRecorderConfigured()
      throws Exception {
    MetricsRecorder r = new LastSampleMetricsRecorder();
    CountingAllocator allocator = allocator(alloc($new, $throw(new Exception())));
    builder = Pool.from(allocator);
    builder.setMetricsRecorder(r);
    Reallocator<GenericPoolable> adaptedReallocator = getAdaptedReallocator();
    verifyLatencies(r, isNaN(), isNaN(), isNaN(), isNaN(), isNaN());
    adaptedReallocator.allocate(new NullSlot());
    verifyLatencies(r, not(isNaN()), isNaN(), isNaN(), isNaN(), isNaN());
    try {
      adaptedReallocator.allocate(new NullSlot());
      fail("second allocation did not throw as expected");
    } catch (Exception ignore) {}
    verifyLatencies(r, not(isNaN()), not(isNaN()), isNaN(), isNaN(), isNaN());
  }

  @Test
  void getAdaptedReallocatorMustInstrumentAllocateMethodOnReallocatorIfMetricsRecorderConfigured()
      throws Exception {
    MetricsRecorder r = new LastSampleMetricsRecorder();
    CountingReallocator reallocator = reallocator(alloc($new, $throw(new Exception())));
    builder = Pool.from(reallocator);
    builder.setMetricsRecorder(r);
    Reallocator<GenericPoolable> adaptedReallocator = getAdaptedReallocator();
    verifyLatencies(r, isNaN(), isNaN(), isNaN(), isNaN(), isNaN());
    adaptedReallocator.allocate(new NullSlot());
    verifyLatencies(r, not(isNaN()), isNaN(), isNaN(), isNaN(), isNaN());
    try {
      adaptedReallocator.allocate(new NullSlot());
      fail("allocation did not throw as expected");
    } catch (Exception ignore) {}
    verifyLatencies(r, not(isNaN()), not(isNaN()), isNaN(), isNaN(), isNaN());
  }

  @Test
  void getAdaptedReallocatorMustInstrumentDeallocateMethodOnAllocatorIfMetricsRecorderConfigured()
      throws Exception {
    MetricsRecorder r = new LastSampleMetricsRecorder();
    CountingAllocator allocator = allocator();
    builder = Pool.from(allocator);
    builder.setMetricsRecorder(r);
    Reallocator<GenericPoolable> adaptedReallocator = getAdaptedReallocator();
    verifyLatencies(r, isNaN(), isNaN(), isNaN(), isNaN(), isNaN());
    GenericPoolable obj = adaptedReallocator.allocate(new NullSlot());
    verifyLatencies(r, not(isNaN()), isNaN(), isNaN(), isNaN(), isNaN());
    adaptedReallocator.deallocate(obj);
    verifyLatencies(r, not(isNaN()), isNaN(), not(isNaN()), isNaN(), isNaN());
  }

  @Test
  void getAdaptedReallocatorMustInstrumentThrowingDeallocateMethodOnAllocatorIfMetricsRecorderConfigured()
      throws Exception {
    MetricsRecorder r = new LastSampleMetricsRecorder();
    CountingAllocator allocator = allocator(dealloc($throw(new Exception())));
    builder = Pool.from(allocator);
    builder.setMetricsRecorder(r);
    Reallocator<GenericPoolable> adaptedReallocator = getAdaptedReallocator();
    verifyLatencies(r, isNaN(), isNaN(), isNaN(), isNaN(), isNaN());
    GenericPoolable obj = adaptedReallocator.allocate(new NullSlot());
    verifyLatencies(r, not(isNaN()), isNaN(), isNaN(), isNaN(), isNaN());
    try {
      adaptedReallocator.deallocate(obj);
      fail("deallocation did not throw as expected");
    } catch (Exception e) {
      // ignore
    }
    verifyLatencies(r, not(isNaN()), isNaN(), not(isNaN()), isNaN(), isNaN());
  }

  @Test
  void getAdaptedReallocatorMustInstrumentDeallocateMethodOnReallocatorIfMetricsRecorderConfigured()
      throws Exception {
    MetricsRecorder r = new LastSampleMetricsRecorder();
    CountingReallocator reallocator = reallocator();
    builder = Pool.from(reallocator);
    builder.setMetricsRecorder(r);
    Reallocator<GenericPoolable> adaptedReallocator = getAdaptedReallocator();
    verifyLatencies(r, isNaN(), isNaN(), isNaN(), isNaN(), isNaN());
    GenericPoolable obj = adaptedReallocator.allocate(new NullSlot());
    verifyLatencies(r, not(isNaN()), isNaN(), isNaN(), isNaN(), isNaN());
    adaptedReallocator.deallocate(obj);
    verifyLatencies(r, not(isNaN()), isNaN(), not(isNaN()), isNaN(), isNaN());
  }

  @Test
  void getAdaptedReallocatorMustInstrumentThrowingDeallocateMethodOnReallocatorIfMetricsRecorderConfigured()
      throws Exception {
    MetricsRecorder r = new LastSampleMetricsRecorder();
    CountingReallocator reallocator = reallocator(dealloc($throw(new Exception())));
    builder = Pool.from(reallocator);
    builder.setMetricsRecorder(r);
    Reallocator<GenericPoolable> adaptedReallocator = getAdaptedReallocator();
    verifyLatencies(r, isNaN(), isNaN(), isNaN(), isNaN(), isNaN());
    GenericPoolable obj = adaptedReallocator.allocate(new NullSlot());
    verifyLatencies(r, not(isNaN()), isNaN(), isNaN(), isNaN(), isNaN());
    try {
      adaptedReallocator.deallocate(obj);
      fail("deallocation did not throw as expected");
    } catch (Exception e) {
      // ignore
    }
    verifyLatencies(r, not(isNaN()), isNaN(), not(isNaN()), isNaN(), isNaN());
  }

  @Test
  void getAdaptedReallocatorMustInstrumentReallocateMethodOnReallocatorIfMetricsRecorderConfigured()
      throws Exception {
    MetricsRecorder r = new LastSampleMetricsRecorder();
    CountingReallocator reallocator = reallocator(realloc($new, $throw(new Exception())));
    builder = Pool.from(reallocator);
    builder.setMetricsRecorder(r);
    Reallocator<GenericPoolable> adaptedReallocator = getAdaptedReallocator();
    verifyLatencies(r, isNaN(), isNaN(), isNaN(), isNaN(), isNaN());
    GenericPoolable obj = adaptedReallocator.allocate(new NullSlot());
    verifyLatencies(r, not(isNaN()), isNaN(), isNaN(), isNaN(), isNaN());
    adaptedReallocator.reallocate(new NullSlot(), obj);
    verifyLatencies(r, not(isNaN()), isNaN(), isNaN(), not(isNaN()), isNaN());
    try {
      adaptedReallocator.reallocate(new NullSlot(), obj);
      fail("reallocation did not throw as expected");
    } catch (Exception ignore) {}
    verifyLatencies(r, not(isNaN()), isNaN(), isNaN(), not(isNaN()), not(isNaN()));
  }

  private void verifyLatencies(
      MetricsRecorder recorder,
      Condition<Double> alloc, Condition<Double> allocFail,
      Condition<Double> dealloc,
      Condition<Double> realloc, Condition<Double> reallocFail) {
    assertThat(recorder.getAllocationLatencyPercentile(0.0)).is(alloc);
    assertThat(recorder.getAllocationFailureLatencyPercentile(0.0)).is(allocFail);
    assertThat(recorder.getDeallocationLatencyPercentile(0.0)).is(dealloc);
    assertThat(recorder.getReallocationLatencyPercentile(0.0)).is(realloc);
    assertThat(recorder.getReallocationFailurePercentile(0.0)).is(reallocFail);
  }

  private Condition<Double> isNaN() {
    return new Condition<>(value -> Double.isNaN(value), "isNaN");
  }
}
