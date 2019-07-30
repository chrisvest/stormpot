/*
 * Copyright © 2011-2019 Chris Vest (mr.chrisvest@gmail.com)
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

import org.assertj.core.api.Condition;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ThreadFactory;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.condition.Not.not;
import static org.junit.jupiter.api.Assertions.*;
import static stormpot.AlloKit.*;

class ConfigTest {
  private Config<GenericPoolable> config;
  
  @BeforeEach
  void setUp() {
    config = new Config<>();
  }
  
  @Test
  void sizeMustBeSettable() {
    config.setSize(123);
    assertEquals(123, config.getSize());
  }

  @Test
  void defaultAllocatorIsNull() {
    assertThat(config.getAllocator()).isNull();
  }

  @Test
  void defaultReallocatorIsNull() {
    assertThat(config.getReallocator()).isNull();
  }

  @Test
  void mustAdaptAllocatorsToReallocators() {
    Allocator<GenericPoolable> allocator = allocator();
    Config<GenericPoolable> cfg = config.setAllocator(allocator);
    Reallocator<GenericPoolable> reallocator = cfg.getReallocator();
    ReallocatingAdaptor<GenericPoolable> adaptor =
        (ReallocatingAdaptor<GenericPoolable>) reallocator;
    assertThat(adaptor.unwrap()).isSameAs(allocator);
  }

  @Test
  void mustNotReAdaptConfiguredReallocators() {
    Reallocator<GenericPoolable> expected =
        new ReallocatingAdaptor<>(null);
    config.setAllocator(expected);
    Reallocator<GenericPoolable> actual = config.getReallocator();
    assertThat(actual).isSameAs(expected);
  }

  @Test
  void allocatorMustBeSettable() {
    Allocator<GenericPoolable> allocator = allocator();
    Config<GenericPoolable> cfg = config.setAllocator(allocator);
    assertThat(cfg.getAllocator()).isSameAs(allocator);
  }
  
  @Test
  void mustHaveTimeBasedDeallocationRuleAsDefault() {
    assertThat(config.getExpiration()).isInstanceOf(TimeSpreadExpiration.class);
  }
  
  @Test
  void deallocationRuleMustBeSettable() {
    Expiration<Poolable> expectedRule = info -> false;
    config.setExpiration(expectedRule);
    @SuppressWarnings("unchecked")
    Expiration<Poolable> actualRule =
        (Expiration<Poolable>) config.getExpiration();
    assertThat(actualRule).isEqualTo(expectedRule);
  }

  @Test
  void metricsRecorderMustBeSettable() {
    MetricsRecorder expected =
        new FixedMeanMetricsRecorder(1.0, 1.0, 1.0, 1.0, 1.0, 1.0);
    config.setMetricsRecorder(expected);
    MetricsRecorder actual = config.getMetricsRecorder();
    assertThat(actual).isEqualTo(expected);
  }

  @Test
  void getAdaptedReallocatorMustReturnNullIfNoAllocatorConfigured() {
    assertNull(config.getAdaptedReallocator());
  }

  @Test
  void getAdaptedReallocatorMustReturnNullWhenNoAllocatorConfiguredEvenIfMetricsRecorderIsConfigured() {
    config.setMetricsRecorder(new LastSampleMetricsRecorder());
    assertNull(config.getAdaptedReallocator());
  }

  @Test
  void getAdaptedReallocatorMustAdaptConfiguredAllocatorIfNoMetricsRecorderConfigured()
      throws Exception {
    CountingAllocator allocator = allocator();
    config.setAllocator(allocator);
    config.getAdaptedReallocator().allocate(new NullSlot());
    assertThat(allocator.countAllocations()).isOne();
  }

  @Test
  void getAdaptedReallocatorMustNotAdaptConfiguredReallocatorIfNoMetricsRecorderConfigured()
      throws Exception {
    CountingReallocator reallocator = reallocator();
    config.setAllocator(reallocator);
    Slot slot = new NullSlot();
    Reallocator<GenericPoolable> adaptedReallocator = config.getAdaptedReallocator();
    GenericPoolable obj = adaptedReallocator.allocate(slot);
    adaptedReallocator.reallocate(slot, obj);

    assertThat(reallocator.countAllocations()).isOne();
    assertThat(reallocator.countReallocations()).isOne();
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

  @Test
  void getAdaptedReallocatorMustInstrumentAllocateMethodOnAllocatorIfMetricsRecorderConfigured()
      throws Exception {
    MetricsRecorder r = new LastSampleMetricsRecorder();
    config.setMetricsRecorder(r);
    config.setAllocator(allocator(alloc($new, $throw(new Exception()))));
    Reallocator<GenericPoolable> adaptedReallocator = config.getAdaptedReallocator();
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
    config.setMetricsRecorder(r);
    config.setAllocator(reallocator(alloc($new, $throw(new Exception()))));
    Reallocator<GenericPoolable> adaptedReallocator = config.getAdaptedReallocator();
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
    config.setMetricsRecorder(r);
    config.setAllocator(allocator());
    Reallocator<GenericPoolable> adaptedReallocator = config.getAdaptedReallocator();
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
    config.setMetricsRecorder(r);
    config.setAllocator(allocator(dealloc($throw(new Exception()))));
    Reallocator<GenericPoolable> adaptedReallocator = config.getAdaptedReallocator();
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
    config.setMetricsRecorder(r);
    config.setAllocator(reallocator());
    Reallocator<GenericPoolable> adaptedReallocator = config.getAdaptedReallocator();
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
    config.setMetricsRecorder(r);
    config.setAllocator(reallocator(dealloc($throw(new Exception()))));
    Reallocator<GenericPoolable> adaptedReallocator = config.getAdaptedReallocator();
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
    config.setMetricsRecorder(r);
    config.setAllocator(reallocator(realloc($new, $throw(new Exception()))));
    Reallocator<GenericPoolable> adaptedReallocator = config.getAdaptedReallocator();
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

  @Test
  void defaultThreadFactoryMustCreateThreadsWithStormpotNameSignature() {
    ThreadFactory factory = config.getThreadFactory();
    Thread thread = factory.newThread(() -> {});
    assertThat(thread.getName()).contains("Stormpot");
  }

  @Test
  void threadFactoryMustBeSettable() {
    ThreadFactory factory = r -> null;
    config.setThreadFactory(factory);
    assertThat(config.getThreadFactory()).isSameAs(factory);
  }

  @Test
  void preciseLeakDetectionMustBeEnabledByDefault() {
    assertTrue(config.isPreciseLeakDetectionEnabled());
  }

  @Test
  void preciseLeakDetectionMustBeSettable() {
    config.setPreciseLeakDetectionEnabled(false);
    assertFalse(config.isPreciseLeakDetectionEnabled());
  }

  @Test
  void backgroundExpirationIsDisabledByDefault() {
    assertFalse(config.isBackgroundExpirationEnabled());
  }

  @Test
  void backgroundExpirationMystBeSettable() {
    config.setBackgroundExpirationEnabled(true);
    assertTrue(config.isBackgroundExpirationEnabled());
  }

  @Test
  void allSetterMethodsMustReturnTheSameConfigInstance() throws Exception {
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
      assertThat(result).as("return value of setter " + setter).isSameAs(config);
    }
  }

  @Test
  void allPublicDeclaredMethodsMustBeSynchronized() {
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
        assertTrue(Modifier.isSynchronized(modifiers), "Method '" + method + "' should be synchronized.");
      }
    }
  }

  @Test
  void configMustBeCloneable() {
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

    assertThat(clone.getExpiration()).isSameAs(expiration);
    assertThat(clone.getAllocator()).isSameAs(allocator);
    assertTrue(clone.isBackgroundExpirationEnabled());
    assertThat(clone.getMetricsRecorder()).isSameAs(metricsRecorder);
    assertFalse(clone.isPreciseLeakDetectionEnabled());
    assertThat(clone.getSize()).isEqualTo(42);
    assertThat(clone.getThreadFactory()).isSameAs(factory);
  }
}
