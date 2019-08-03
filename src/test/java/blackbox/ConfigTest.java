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
package blackbox;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import stormpot.*;

import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ThreadFactory;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.*;
import static stormpot.AlloKit.CountingAllocator;
import static stormpot.AlloKit.allocator;

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
