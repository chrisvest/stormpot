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
package stormpot.tests;

import com.codahale.metrics.MetricRegistry;
import examples.DropwizardMetricsRecorder;
import org.assertj.core.api.Assertions;
import org.junit.jupiter.api.Test;
import stormpot.Allocator;
import stormpot.Expiration;
import stormpot.internal.AllocationProcessMode;
import stormpot.internal.PoolBuilderImpl;
import testkits.AlloKit;
import testkits.GenericPoolable;

import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static stormpot.internal.AllocationProcess.direct;
import static stormpot.internal.AllocationProcess.threaded;

class WhiteboxPoolBuilderTest {
  private final Allocator<GenericPoolable> allocator = AlloKit.allocator();

  @Test
  void defaultsMapMustMapAllKeys() {
    for (AllocationProcessMode mode : AllocationProcessMode.values()) {
      Assertions.assertThat(PoolBuilderImpl.DEFAULTS).containsKey(mode);
    }
  }

  @Test
  void permissionsMapMustMapAllKeys() {
    for (AllocationProcessMode mode : AllocationProcessMode.values()) {
      assertThat(PoolBuilderImpl.PERMISSIONS).containsKey(mode);
    }
  }

  @Test
  void threadedProcessAllowSettingAllocator() {
    AlloKit.CountingAllocator alloc = AlloKit.allocator();
    var pb = new PoolBuilderImpl<>(threaded(), allocator).setAllocator(alloc);
    assertThat(pb.getAllocator()).isSameAs(alloc);
  }

  @Test
  void threadedProcessAllowSettingSize() {
    var pb = new PoolBuilderImpl<>(threaded(), allocator).setSize(100);
    assertThat(pb.getSize()).isEqualTo(100);
  }

  @Test
  void threadedProcessAllowSettingExpiration() {
    var exp = Expiration.after(2, TimeUnit.SECONDS);
    var pb = new PoolBuilderImpl<>(threaded(), allocator).setExpiration(exp);
    assertThat(pb.getExpiration()).isSameAs(exp);
  }

  @Test
  void threadedProcessAllowSettingThreadFactory() {
    ThreadFactory factory = Thread::new;
    var pb = new PoolBuilderImpl<>(threaded(), allocator).setThreadFactory(factory);
    assertThat(pb.getThreadFactory()).isSameAs(factory);
  }

  @Test
  void threadedProcessAllowSettingBackgroundExpiration() {
    var pb = new PoolBuilderImpl<>(threaded(), allocator);
    boolean enabled = !pb.isBackgroundExpirationEnabled();
    pb.setBackgroundExpirationEnabled(enabled);
    assertThat(pb.isBackgroundExpirationEnabled()).isEqualTo(enabled);
  }

  @Test
  void threadedProcessAllowSettingBackgroundExpirationCheckDelay() {
    var pb = new PoolBuilderImpl<>(threaded(), allocator).setBackgroundExpirationCheckDelay(42);
    assertThat(pb.getBackgroundExpirationCheckDelay()).isEqualTo(42);
  }

  @Test
  void threadedProcessAllowSettingMetricsRecorder() {
    var recorder = new DropwizardMetricsRecorder("base", new MetricRegistry());
    var pb = new PoolBuilderImpl<>(threaded(), allocator).setMetricsRecorder(recorder);
    assertThat(pb.getMetricsRecorder()).isSameAs(recorder);
  }

  @Test
  void directProcessAllowSettingSize() {
    var pb = new PoolBuilderImpl<>(direct(), allocator).setSize(100);
    assertThat(pb.getSize()).isEqualTo(100);
  }

  @Test
  void directProcessDisallowSettingExpiration() {
    var pb = new PoolBuilderImpl<>(direct(), allocator);
    assertThrows(IllegalStateException.class, () -> pb.setExpiration(Expiration.never()));
  }

  @Test
  void directProcessDisallowSettingAllocator() {
    var pb = new PoolBuilderImpl<>(direct(), allocator);
    assertThrows(IllegalStateException.class, () -> pb.setAllocator(AlloKit.allocator()));
  }

  @Test
  void directProcessDisallowSettingThreadFactory() {
    var pb = new PoolBuilderImpl<>(direct(), allocator);
    assertThrows(IllegalStateException.class, () -> pb.setThreadFactory(r -> null));
  }

  @Test
  void directProcessDisallowSettingBackgroundExpiration() {
    var pb = new PoolBuilderImpl<>(direct(), allocator);
    assertThrows(IllegalStateException.class, () -> pb.setBackgroundExpirationEnabled(false));
    assertThrows(IllegalStateException.class, () -> pb.setBackgroundExpirationEnabled(true));
  }

  @Test
  void directProcessDisallowSettingBackgroundExpirationCheckDelay() {
    var pb = new PoolBuilderImpl<>(direct(), allocator);
    assertThrows(IllegalStateException.class, () -> pb.setBackgroundExpirationCheckDelay(42));
  }

  @Test
  void directProcessAllowSettingMetricsRecorder() {
    var recorder = new DropwizardMetricsRecorder("base", new MetricRegistry());
    var pb = new PoolBuilderImpl<>(direct(), allocator).setMetricsRecorder(recorder);
    assertThat(pb.getMetricsRecorder()).isSameAs(recorder);
  }
}
