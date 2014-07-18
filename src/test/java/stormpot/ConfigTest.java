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

import org.hamcrest.Matcher;
import org.junit.Before;
import org.junit.Test;

import static java.lang.Double.NaN;
import static org.hamcrest.Matchers.*;
import static org.junit.Assert.*;

public class ConfigTest {
  private Config<Poolable> config;
  
  @Before public void
  setUp() {
    config = new Config<Poolable>();
  }
  
  @Test public void
  sizeMustBeSettable() {
    config.setSize(123);
    assertTrue(config.getSize() == 123);
  }

  @Test public void
  defaultAllocatorIsNull() {
    assertThat(config.getAllocator(), is(nullValue()));
  }

  @Test public void
  defaultReallocatorIsNull() {
    assertThat(config.getReallocator(), is(nullValue()));
  }

  @Test public void
  mustAdaptAllocatorsToReallocators() {
    Allocator<GenericPoolable> allocator = new CountingAllocator();
    Config<GenericPoolable> cfg = config.setAllocator(allocator);
    Reallocator<GenericPoolable> reallocator = cfg.getReallocator();
    ReallocatingAdaptor<GenericPoolable> adaptor =
        (ReallocatingAdaptor<GenericPoolable>) reallocator;
    assertThat(adaptor.unwrap(), sameInstance(allocator));
  }

  @Test public void
  mustNotReAdaptConfiguredReallocators() {
    Reallocator<Poolable> expected = new ReallocatingAdaptor<Poolable>(null);
    config.setAllocator(expected);
    Reallocator<Poolable> actual = config.getReallocator();
    assertThat(actual, sameInstance(expected));
  }

  @Test public void
  allocatorMustBeSettable() {
    Allocator<GenericPoolable> allocator = new CountingAllocator();
    Config<GenericPoolable> cfg = config.setAllocator(allocator);
    assertThat(cfg.getAllocator(), sameInstance(allocator));
  }
  
  @Test public void
  mustHaveTimeBasedDeallocationRuleAsDefault() {
    assertThat(config.getExpiration(),
        instanceOf(TimeSpreadExpiration.class));
  }
  
  @Test public void
  deallocationRuleMustBeSettable() {
    Expiration<Poolable> expectedRule = new Expiration<Poolable>() {
      public boolean hasExpired(SlotInfo<? extends Poolable> info) {
        return false;
      }
    };
    config.setExpiration(expectedRule);
    @SuppressWarnings("unchecked")
    Expiration<Poolable> actualRule =
        (Expiration<Poolable>) config.getExpiration();
    assertThat(actualRule, is(expectedRule));
  }

  @Test public void
  latencyRecorderMustBeSettable() {
    LatencyRecorder expected =
        new FixedMeanLatencyRecorder(1.0, 1.0, 1.0, 1.0, 1.0, 1.0);
    config.setLatencyRecorder(expected);
    LatencyRecorder actual = config.getLatencyRecorder();
    assertThat(actual, is(expected));
  }

  @Test public void
  getAdaptedReallocatorMustReturnNullIfNoAllocatorConfigured() {
    assertNull(config.getAdaptedReallocator());
  }

  @Test public void
  getAdaptedReallocatorMustReturnNullWhenNoAllocatorConfiguredEvenIfLatencyRecorderIsConfigured() {
    config.setLatencyRecorder(new LastSampleLatencyRecorder());
    assertNull(config.getAdaptedReallocator());
  }

  @Test public void
  getAdaptedReallocatorMustAdaptConfiguredAllocatorIfNoLatencyRecorderConfigured()
      throws Exception {
    CountingAllocator allocator = new CountingAllocator();
    config.setAllocator(allocator);
    config.getAdaptedReallocator().allocate(new NullSlot());
    assertThat(allocator.allocations(), is(1));
  }

  @Test public void
  getAdaptedReallocatorMustNotAdaptConfiguredReallocatorIfNoLatencyRecorderConfigured()
      throws Exception {
    CountingReallocator reallocator = new CountingReallocator();
    config.setAllocator(reallocator);
    Slot slot = new NullSlot();
    Reallocator<Poolable> adaptedReallocator = config.getAdaptedReallocator();
    Poolable obj = adaptedReallocator.allocate(slot);
    adaptedReallocator.reallocate(slot, obj);

    assertThat(reallocator.allocations(), is(1));
    assertThat(reallocator.reallocations(), is(1));
  }

  private void verifyLatencies(
      LatencyRecorder recorder,
      Matcher<Double> alloc, Matcher<Double> allocFail,
      Matcher<Double> dealloc,
      Matcher<Double> realloc, Matcher<Double> reallocFail) {
    assertThat(recorder.getAllocationLatencyPercentile(0.0), alloc);
    assertThat(recorder.getAllocationFailureLatencyPercentile(0.0), allocFail);
    assertThat(recorder.getDeallocationLatencyPercentile(0.0), dealloc);
    assertThat(recorder.getReallocationLatencyPercentile(0.0), realloc);
    assertThat(recorder.getReallocationFailurePercentile(0.0), reallocFail);
  }

  @Test public void
  getAdaptedReallocatorMustInstrumentAllocateMethodOnAllocatorIfLatencyRecorderConfigured()
      throws Exception {
    LatencyRecorder r = new LastSampleLatencyRecorder();
    config.setLatencyRecorder(r);
    config.setAllocator(new FallibleAllocator(new Exception(), true, false));
    Reallocator<Poolable> adaptedReallocator = config.getAdaptedReallocator();
    verifyLatencies(r, is(NaN), is(NaN), is(NaN), is(NaN), is(NaN));
    adaptedReallocator.allocate(new NullSlot());
    verifyLatencies(r, is(not(NaN)), is(NaN), is(NaN), is(NaN), is(NaN));
    try {
      adaptedReallocator.allocate(new NullSlot());
      fail("second allocation did not throw as expected");
    } catch (Exception ignore) {}
    verifyLatencies(r, is(not(NaN)), is(not(NaN)), is(NaN), is(NaN), is(NaN));
  }

  @Test public void
  getAdaptedReallocatorMustInstrumentAllocateMethodOnReallocatorIfLatencyRecorderConfigured()
      throws Exception {
    LatencyRecorder r = new LastSampleLatencyRecorder();
    config.setLatencyRecorder(r);
    config.setAllocator(new FallibleReallocator(new Exception(), true, false));
    Reallocator<Poolable> adaptedReallocator = config.getAdaptedReallocator();
    verifyLatencies(r, is(NaN), is(NaN), is(NaN), is(NaN), is(NaN));
    adaptedReallocator.allocate(new NullSlot());
    verifyLatencies(r, is(not(NaN)), is(NaN), is(NaN), is(NaN), is(NaN));
    try {
      adaptedReallocator.allocate(new NullSlot());
      fail("allocation did not throw as expected");
    } catch (Exception ignore) {}
    verifyLatencies(r, is(not(NaN)), is(not(NaN)), is(NaN), is(NaN), is(NaN));
  }

  @Test public void
  getAdaptedReallocatorMustInstrumentDeallocateMethodOnAllocatorIfLatencyRecorderConfigured()
      throws Exception {
    LatencyRecorder r = new LastSampleLatencyRecorder();
    config.setLatencyRecorder(r);
    config.setAllocator(new FallibleDeallocator(new Exception(), true));
    Reallocator<Poolable> adaptedReallocator = config.getAdaptedReallocator();
    verifyLatencies(r, is(NaN), is(NaN), is(NaN), is(NaN), is(NaN));
    Poolable obj = adaptedReallocator.allocate(new NullSlot());
    verifyLatencies(r, is(not(NaN)), is(NaN), is(NaN), is(NaN), is(NaN));
    adaptedReallocator.deallocate(obj);
    verifyLatencies(r, is(not(NaN)), is(NaN), is(not(NaN)), is(NaN), is(NaN));
  }

  @Test public void
  getAdaptedReallocatorMustInstrumentThrowingDeallocateMethodOnAllocatorIfLatencyRecorderConfigured()
      throws Exception {
    LatencyRecorder r = new LastSampleLatencyRecorder();
    config.setLatencyRecorder(r);
    config.setAllocator(new FallibleDeallocator(new Exception(), false));
    Reallocator<Poolable> adaptedReallocator = config.getAdaptedReallocator();
    verifyLatencies(r, is(NaN), is(NaN), is(NaN), is(NaN), is(NaN));
    Poolable obj = adaptedReallocator.allocate(new NullSlot());
    verifyLatencies(r, is(not(NaN)), is(NaN), is(NaN), is(NaN), is(NaN));
    try {
      adaptedReallocator.deallocate(obj);
      fail("deallocation did not throw as expected");
    } catch (Exception e) {
      // ignore
    }
    verifyLatencies(r, is(not(NaN)), is(NaN), is(not(NaN)), is(NaN), is(NaN));
  }

  @Test public void
  getAdaptedReallocatorMustInstrumentDeallocateMethodOnRellocatorIfLatencyRecorderConfigured()
      throws Exception {
    LatencyRecorder r = new LastSampleLatencyRecorder();
    config.setLatencyRecorder(r);
    config.setAllocator(new FallibleDeReallocator(new Exception(), true));
    Reallocator<Poolable> adaptedReallocator = config.getAdaptedReallocator();
    verifyLatencies(r, is(NaN), is(NaN), is(NaN), is(NaN), is(NaN));
    Poolable obj = adaptedReallocator.allocate(new NullSlot());
    verifyLatencies(r, is(not(NaN)), is(NaN), is(NaN), is(NaN), is(NaN));
    adaptedReallocator.deallocate(obj);
    verifyLatencies(r, is(not(NaN)), is(NaN), is(not(NaN)), is(NaN), is(NaN));
  }

  @Test public void
  getAdaptedReallocatorMustInstrumentThrowingDeallocateMethodOnRellocatorIfLatencyRecorderConfigured()
      throws Exception {
    LatencyRecorder r = new LastSampleLatencyRecorder();
    config.setLatencyRecorder(r);
    config.setAllocator(new FallibleDeReallocator(new Exception(), false));
    Reallocator<Poolable> adaptedReallocator = config.getAdaptedReallocator();
    verifyLatencies(r, is(NaN), is(NaN), is(NaN), is(NaN), is(NaN));
    Poolable obj = adaptedReallocator.allocate(new NullSlot());
    verifyLatencies(r, is(not(NaN)), is(NaN), is(NaN), is(NaN), is(NaN));
    try {
      adaptedReallocator.deallocate(obj);
      fail("deallocation did not throw as expected");
    } catch (Exception e) {
      // ignore
    }
    verifyLatencies(r, is(not(NaN)), is(NaN), is(not(NaN)), is(NaN), is(NaN));
  }

  @Test public void
  getAdaptedReallocatorMustInstrumentReallocateMethodOnReallocatorIfLatencyRecorderConfigured()
      throws Exception {
    LatencyRecorder r = new LastSampleLatencyRecorder();
    config.setLatencyRecorder(r);
    config.setAllocator(new FallibleReallocator(new Exception(), true, true, false));
    Reallocator<Poolable> adaptedReallocator = config.getAdaptedReallocator();
    verifyLatencies(r, is(NaN), is(NaN), is(NaN), is(NaN), is(NaN));
    Poolable obj = adaptedReallocator.allocate(new NullSlot());
    verifyLatencies(r, is(not(NaN)), is(NaN), is(NaN), is(NaN), is(NaN));
    adaptedReallocator.reallocate(new NullSlot(), obj);
    verifyLatencies(r, is(not(NaN)), is(NaN), is(NaN), is(not(NaN)), is(NaN));
    try {
      adaptedReallocator.reallocate(new NullSlot(), obj);
      fail("reallocation did not throw as expected");
    } catch (Exception ignore) {}
    verifyLatencies(r, is(not(NaN)), is(NaN), is(NaN), is(not(NaN)), is(not(NaN)));
  }
}
