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

import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ThreadFactory;

import static java.lang.Double.NaN;
import static org.hamcrest.Matchers.*;
import static org.junit.Assert.*;
import static stormpot.AlloKit.*;

public class ConfigTest {
  private Config<GenericPoolable> config;
  
  @Before public void
  setUp() {
    config = new Config<>();
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
    Allocator<GenericPoolable> allocator = allocator();
    Config<GenericPoolable> cfg = config.setAllocator(allocator);
    Reallocator<GenericPoolable> reallocator = cfg.getReallocator();
    ReallocatingAdaptor<GenericPoolable> adaptor =
        (ReallocatingAdaptor<GenericPoolable>) reallocator;
    assertThat(adaptor.unwrap(), sameInstance(allocator));
  }

  @Test public void
  mustNotReAdaptConfiguredReallocators() {
    Reallocator<GenericPoolable> expected =
        new ReallocatingAdaptor<>(null);
    config.setAllocator(expected);
    Reallocator<GenericPoolable> actual = config.getReallocator();
    assertThat(actual, sameInstance(expected));
  }

  @Test public void
  allocatorMustBeSettable() {
    Allocator<GenericPoolable> allocator = allocator();
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
    Expiration<Poolable> expectedRule = info -> false;
    config.setExpiration(expectedRule);
    @SuppressWarnings("unchecked")
    Expiration<Poolable> actualRule =
        (Expiration<Poolable>) config.getExpiration();
    assertThat(actualRule, is(expectedRule));
  }

  @Test public void
  metricsRecorderMustBeSettable() {
    MetricsRecorder expected =
        new FixedMeanMetricsRecorder(1.0, 1.0, 1.0, 1.0, 1.0, 1.0);
    config.setMetricsRecorder(expected);
    MetricsRecorder actual = config.getMetricsRecorder();
    assertThat(actual, is(expected));
  }

  @Test public void
  getAdaptedReallocatorMustReturnNullIfNoAllocatorConfigured() {
    assertNull(config.getAdaptedReallocator());
  }

  @Test public void
  getAdaptedReallocatorMustReturnNullWhenNoAllocatorConfiguredEvenIfMetricsRecorderIsConfigured() {
    config.setMetricsRecorder(new LastSampleMetricsRecorder());
    assertNull(config.getAdaptedReallocator());
  }

  @Test public void
  getAdaptedReallocatorMustAdaptConfiguredAllocatorIfNoMetricsRecorderConfigured()
      throws Exception {
    CountingAllocator allocator = allocator();
    config.setAllocator(allocator);
    config.getAdaptedReallocator().allocate(new NullSlot());
    assertThat(allocator.countAllocations(), is(1));
  }

  @Test public void
  getAdaptedReallocatorMustNotAdaptConfiguredReallocatorIfNoMetricsRecorderConfigured()
      throws Exception {
    CountingReallocator reallocator = reallocator();
    config.setAllocator(reallocator);
    Slot slot = new NullSlot();
    Reallocator<GenericPoolable> adaptedReallocator = config.getAdaptedReallocator();
    GenericPoolable obj = adaptedReallocator.allocate(slot);
    adaptedReallocator.reallocate(slot, obj);

    assertThat(reallocator.countAllocations(), is(1));
    assertThat(reallocator.countReallocations(), is(1));
  }

  private void verifyLatencies(
      MetricsRecorder recorder,
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
  getAdaptedReallocatorMustInstrumentAllocateMethodOnAllocatorIfMetricsRecorderConfigured()
      throws Exception {
    MetricsRecorder r = new LastSampleMetricsRecorder();
    config.setMetricsRecorder(r);
    config.setAllocator(allocator(alloc($new, $throw(new Exception()))));
    Reallocator<GenericPoolable> adaptedReallocator = config.getAdaptedReallocator();
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
  getAdaptedReallocatorMustInstrumentAllocateMethodOnReallocatorIfMetricsRecorderConfigured()
      throws Exception {
    MetricsRecorder r = new LastSampleMetricsRecorder();
    config.setMetricsRecorder(r);
    config.setAllocator(reallocator(alloc($new, $throw(new Exception()))));
    Reallocator<GenericPoolable> adaptedReallocator = config.getAdaptedReallocator();
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
  getAdaptedReallocatorMustInstrumentDeallocateMethodOnAllocatorIfMetricsRecorderConfigured()
      throws Exception {
    MetricsRecorder r = new LastSampleMetricsRecorder();
    config.setMetricsRecorder(r);
    config.setAllocator(allocator());
    Reallocator<GenericPoolable> adaptedReallocator = config.getAdaptedReallocator();
    verifyLatencies(r, is(NaN), is(NaN), is(NaN), is(NaN), is(NaN));
    GenericPoolable obj = adaptedReallocator.allocate(new NullSlot());
    verifyLatencies(r, is(not(NaN)), is(NaN), is(NaN), is(NaN), is(NaN));
    adaptedReallocator.deallocate(obj);
    verifyLatencies(r, is(not(NaN)), is(NaN), is(not(NaN)), is(NaN), is(NaN));
  }

  @Test public void
  getAdaptedReallocatorMustInstrumentThrowingDeallocateMethodOnAllocatorIfMetricsRecorderConfigured()
      throws Exception {
    MetricsRecorder r = new LastSampleMetricsRecorder();
    config.setMetricsRecorder(r);
    config.setAllocator(allocator(dealloc($throw(new Exception()))));
    Reallocator<GenericPoolable> adaptedReallocator = config.getAdaptedReallocator();
    verifyLatencies(r, is(NaN), is(NaN), is(NaN), is(NaN), is(NaN));
    GenericPoolable obj = adaptedReallocator.allocate(new NullSlot());
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
  getAdaptedReallocatorMustInstrumentDeallocateMethodOnReallocatorIfMetricsRecorderConfigured()
      throws Exception {
    MetricsRecorder r = new LastSampleMetricsRecorder();
    config.setMetricsRecorder(r);
    config.setAllocator(reallocator());
    Reallocator<GenericPoolable> adaptedReallocator = config.getAdaptedReallocator();
    verifyLatencies(r, is(NaN), is(NaN), is(NaN), is(NaN), is(NaN));
    GenericPoolable obj = adaptedReallocator.allocate(new NullSlot());
    verifyLatencies(r, is(not(NaN)), is(NaN), is(NaN), is(NaN), is(NaN));
    adaptedReallocator.deallocate(obj);
    verifyLatencies(r, is(not(NaN)), is(NaN), is(not(NaN)), is(NaN), is(NaN));
  }

  @Test public void
  getAdaptedReallocatorMustInstrumentThrowingDeallocateMethodOnReallocatorIfMetricsRecorderConfigured()
      throws Exception {
    MetricsRecorder r = new LastSampleMetricsRecorder();
    config.setMetricsRecorder(r);
    config.setAllocator(reallocator(dealloc($throw(new Exception()))));
    Reallocator<GenericPoolable> adaptedReallocator = config.getAdaptedReallocator();
    verifyLatencies(r, is(NaN), is(NaN), is(NaN), is(NaN), is(NaN));
    GenericPoolable obj = adaptedReallocator.allocate(new NullSlot());
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
  getAdaptedReallocatorMustInstrumentReallocateMethodOnReallocatorIfMetricsRecorderConfigured()
      throws Exception {
    MetricsRecorder r = new LastSampleMetricsRecorder();
    config.setMetricsRecorder(r);
    config.setAllocator(reallocator(realloc($new, $throw(new Exception()))));
    Reallocator<GenericPoolable> adaptedReallocator = config.getAdaptedReallocator();
    verifyLatencies(r, is(NaN), is(NaN), is(NaN), is(NaN), is(NaN));
    GenericPoolable obj = adaptedReallocator.allocate(new NullSlot());
    verifyLatencies(r, is(not(NaN)), is(NaN), is(NaN), is(NaN), is(NaN));
    adaptedReallocator.reallocate(new NullSlot(), obj);
    verifyLatencies(r, is(not(NaN)), is(NaN), is(NaN), is(not(NaN)), is(NaN));
    try {
      adaptedReallocator.reallocate(new NullSlot(), obj);
      fail("reallocation did not throw as expected");
    } catch (Exception ignore) {}
    verifyLatencies(r, is(not(NaN)), is(NaN), is(NaN), is(not(NaN)), is(not(NaN)));
  }

  @Test public void
  defaultThreadFactoryMustCreateThreadsWithStormpotNameSignature() {
    ThreadFactory factory = config.getThreadFactory();
    Thread thread = factory.newThread(null);
    assertThat(thread.getName(), containsString("Stormpot"));
  }

  @Test public void
  threadFactoryMustBeSettable() {
    ThreadFactory factory = r -> null;
    config.setThreadFactory(factory);
    assertThat(config.getThreadFactory(), sameInstance(factory));
  }

  @Test public void
  preciseLeakDetectionMustBeEnabledByDefault() {
    assertTrue(config.isPreciseLeakDetectionEnabled());
  }

  @Test public void
  preciseLeakDetectionMustBeSettable() {
    config.setPreciseLeakDetectionEnabled(false);
    assertFalse(config.isPreciseLeakDetectionEnabled());
  }

  @Test public void
  backgroundExpirationIsDisabledByDefault() {
    assertFalse(config.isBackgroundExpirationEnabled());
  }

  @Test public void
  backgroundExpirationMystBeSettable() {
    config.setBackgroundExpirationEnabled(true);
    assertTrue(config.isBackgroundExpirationEnabled());
  }

  @Test public void
  allSetterMethodsMustReturnTheSameConfigInstance() throws Exception {
    Method[] methods = Config.class.getDeclaredMethods();
    List<Method> setterMethods = new ArrayList<>();
    for (Method method : methods) {
      if (method.getName().startsWith("set")) {
        setterMethods.add(method);
      }
    }

    for (Method setter : setterMethods) {
      Class<?> parameterType = setter.getParameterTypes()[0];
      Object arg =
          parameterType == Boolean.TYPE? true :
          parameterType == Integer.TYPE? 1 : null;
      Object result = setter.invoke(config, arg);
      assertThat("return value of setter " + setter,
          result, sameInstance((Object) config));
    }
  }

  @Test public void
  allPublicDeclaredMethodsMustBeSynchronized() {
    // We don't care about non-overridden public methods of the super-class
    // (Object) because they don't operate on the state of the Config object
    // anyway.
    Method[] methods = Config.class.getDeclaredMethods();
    for (Method method : methods) {
      int modifiers = method.getModifiers();
      int IS_SYNTHETIC = 0x00001000;
      if (Modifier.isPublic(modifiers) && (modifiers & IS_SYNTHETIC) == 0) {
        // That is, this method is both public AND NOT synthetic.
        // We have to exclude synthetic methods because javac generates one for
        // bridging the covariant override of clone().
        assertTrue("Method '" + method + "' should be synchronized.",
            Modifier.isSynchronized(modifiers));
      }
    }
  }

  @Test public void
  configMustBeCloneable() {
    CountingAllocator allocator = AlloKit.allocator();
    ExpireKit.CountingExpiration expiration = ExpireKit.expire();
    MetricsRecorder metricsRecorder = new LastSampleMetricsRecorder();
    ThreadFactory factory = r -> null;

    config.setExpiration(expiration);
    config.setAllocator(allocator);
    config.setBackgroundExpirationEnabled(true);
    config.setMetricsRecorder(metricsRecorder);
    config.setPreciseLeakDetectionEnabled(false);
    config.setSize(42);
    config.setThreadFactory(factory);

    Config<GenericPoolable> clone = config.clone();

    assertThat(clone.getExpiration(), sameInstance(expiration));
    assertThat(clone.getAllocator(), sameInstance(allocator));
    assertTrue(clone.isBackgroundExpirationEnabled());
    assertThat(clone.getMetricsRecorder(), sameInstance(metricsRecorder));
    assertFalse(clone.isPreciseLeakDetectionEnabled());
    assertThat(clone.getSize(), is(42));
    assertThat(clone.getThreadFactory(), sameInstance(factory));
  }
}
