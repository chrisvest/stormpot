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
package stormpot.tests.blackbox;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import stormpot.internal.PoolBuilderImpl;
import testkits.AlloKit;
import stormpot.Allocator;
import stormpot.Expiration;
import testkits.ExpireKit;
import testkits.FixedMeanMetricsRecorder;
import testkits.GenericPoolable;
import testkits.LastSampleMetricsRecorder;
import stormpot.MetricsRecorder;
import stormpot.Pool;
import stormpot.PoolBuilder;
import stormpot.Poolable;

import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ThreadFactory;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static testkits.AlloKit.CountingAllocator;
import static testkits.AlloKit.allocator;

class PoolBuilderTest {
  private Allocator<GenericPoolable> allocator;
  private PoolBuilder<GenericPoolable> builder;

  @BeforeEach
  void setUp() {
    allocator = allocator();
    builder = Pool.from(allocator);
  }

  @Test
  void sizeMustBeSettable() {
    builder.setSize(123);
    assertEquals(123, builder.getSize());
  }

  @Test
  void allocatorIsGivenByPoolFrom() {
    assertThat(builder.getAllocator()).isSameAs(allocator);
  }

  @Test
  void reallocatorIsNotNull() {
    assertThat(builder.getReallocator()).isNotNull();
  }

  @Test
  void allocatorMustBeSettable() {
    Allocator<GenericPoolable> allocator = allocator();
    PoolBuilder<GenericPoolable> cfg = builder.setAllocator(allocator);
    assertThat(cfg.getAllocator()).isSameAs(allocator);
  }
  
  @Test
  void mustHaveTimeBasedDeallocationRuleAsDefault() {
    assertThat(builder.getExpiration().toString())
        .isEqualTo("TimeSpreadExpiration(8 to 10 MINUTES)");
  }
  
  @Test
  void deallocationRuleMustBeSettable() {
    Expiration<Poolable> expectedRule = info -> false;
    builder.setExpiration(expectedRule);
    @SuppressWarnings("unchecked")
    Expiration<Poolable> actualRule =
        (Expiration<Poolable>) builder.getExpiration();
    assertThat(actualRule).isEqualTo(expectedRule);
  }

  @Test
  void metricsRecorderMustBeSettable() {
    MetricsRecorder expected =
        new FixedMeanMetricsRecorder(1.0, 1.0, 1.0, 1.0, 1.0, 1.0);
    builder.setMetricsRecorder(expected);
    MetricsRecorder actual = builder.getMetricsRecorder();
    assertThat(actual).isEqualTo(expected);
  }

  @Test
  void defaultThreadFactoryMustCreateThreadsWithStormpotNameSignature() {
    ThreadFactory factory = builder.getThreadFactory();
    Thread thread = factory.newThread(() -> {});
    assertThat(thread.getName()).contains("Stormpot");
  }

  @Test
  void threadFactoryMustBeSettable() {
    ThreadFactory factory = r -> null;
    builder.setThreadFactory(factory);
    assertThat(builder.getThreadFactory()).isSameAs(factory);
  }

  @Test
  void preciseLeakDetectionMustBeDisabledByDefault() {
    assertFalse(builder.isPreciseLeakDetectionEnabled());
  }

  @Test
  void preciseLeakDetectionMustBeSettable() {
    builder.setPreciseLeakDetectionEnabled(false);
    assertFalse(builder.isPreciseLeakDetectionEnabled());
  }

  @Test
  void backgroundExpirationIsEnabledByDefault() {
    assertTrue(builder.isBackgroundExpirationEnabled());
  }

  @Test
  void backgroundExpirationMustBeSettable() {
    builder.setBackgroundExpirationEnabled(false);
    assertFalse(builder.isBackgroundExpirationEnabled());
  }

  @Test
  void backgroundExpirationCheckDelayMustBeSettable() {
    builder.setBackgroundExpirationCheckDelay(142);
    assertThat(builder.getBackgroundExpirationCheckDelay()).isEqualTo(142);
  }

  @Test
  void maxConcurrentAllocationsMustBeSettable() {
    builder.setMaxConcurrentAllocations(13);
    assertThat(builder.getMaxConcurrentAllocations()).isEqualTo(13);
  }

  @Test
  void allSetterMethodsMustReturnTheSameBuilderInstance() throws Exception {
    Method[] methods = PoolBuilder.class.getDeclaredMethods();
    List<Method> setterMethods = new ArrayList<>();
    for (Method method : methods) {
      if (method.getName().startsWith("set")) {
        setterMethods.add(method);
      }
    }

    for (Method setter : setterMethods) {
      Class<?> parameterType = setter.getParameterTypes()[0];
      Object arg =
          parameterType == Boolean.TYPE ? true :
          parameterType == Integer.TYPE ? 1 :
          parameterType == Long.TYPE ? 1L :
          parameterType == Allocator.class ? allocator() :
          parameterType == Expiration.class ? Expiration.never() :
          parameterType == ThreadFactory.class ? (ThreadFactory) (r -> null) : null;
      Object result = setter.invoke(builder, arg);
      assertThat(result).as("return value of setter " + setter).isSameAs(builder);
    }
  }

  @Test
  void allPublicDeclaredMethodsMustBeSynchronized() {
    // We don't care about non-overridden public methods of the super-class
    // (Object) because they don't operate on the state of the PoolBuilder object
    // anyway.
    Method[] methods = PoolBuilderImpl.class.getDeclaredMethods();
    for (Method method : methods) {
      int modifiers = method.getModifiers();
      int isSynthetic = 0x00001000;
      if (Modifier.isPublic(modifiers) && (modifiers & isSynthetic) == 0) {
        // That is, this method is both public AND NOT synthetic.
        // We have to exclude synthetic methods because javac generates one for
        // bridging the covariant override of clone().
        assertTrue(Modifier.isSynchronized(modifiers), "Method '" + method + "' should be synchronized.");
      }
    }
  }

  @Test
  void builderMustBeCloneable() {
    CountingAllocator allocator = AlloKit.allocator();
    ExpireKit.CountingExpiration expiration = ExpireKit.expire();
    MetricsRecorder metricsRecorder = new LastSampleMetricsRecorder();
    ThreadFactory factory = r -> null;

    builder.setExpiration(expiration);
    builder.setAllocator(allocator);
    builder.setBackgroundExpirationEnabled(false);
    builder.setBackgroundExpirationCheckDelay(1313);
    builder.setMetricsRecorder(metricsRecorder);
    builder.setPreciseLeakDetectionEnabled(false);
    builder.setSize(42);
    builder.setThreadFactory(factory);
    builder.setMaxConcurrentAllocations(4);

    PoolBuilder<GenericPoolable> clone = builder.clone();

    assertThat(clone.getExpiration()).isSameAs(expiration);
    assertThat(clone.getAllocator()).isSameAs(allocator);
    assertFalse(clone.isBackgroundExpirationEnabled());
    assertThat(clone.getBackgroundExpirationCheckDelay()).isEqualTo(1313);
    assertThat(clone.getMetricsRecorder()).isSameAs(metricsRecorder);
    assertFalse(clone.isPreciseLeakDetectionEnabled());
    assertThat(clone.getSize()).isEqualTo(42);
    assertThat(clone.getThreadFactory()).isSameAs(factory);
    assertThat(clone.getMaxConcurrentAllocations()).isEqualTo(4);
  }
}
