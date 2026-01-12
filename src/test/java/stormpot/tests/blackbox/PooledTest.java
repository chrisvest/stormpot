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

import org.junit.jupiter.api.Test;
import stormpot.Poolable;
import stormpot.Pooled;
import stormpot.Slot;

import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;

class PooledTest {
  @Test
  void pooledIsAutoCloseable() {
    AtomicInteger releaseCounter = new AtomicInteger();
    var reference = new AtomicReference<>();
    Slot slot = new Slot() {
      @Override
      public void release(Poolable obj) {
        releaseCounter.getAndIncrement();
        reference.set(obj);
      }

      @Override
      public void expire(Poolable obj) {
      }
    };

    String object = "bla bla";
    var pooled = new Pooled<>(slot, object);
    try (pooled) {
      assertThat(pooled.object).isSameAs(object);
      assertThat(releaseCounter.get()).isEqualTo(0);
      assertThat(reference.get()).isNull();
    }
    assertThat(releaseCounter.get()).isEqualTo(1);
    assertThat(reference.get()).isSameAs(pooled);
  }

  @Test
  void pooledDelegatesExplicitExpiration() {
    AtomicInteger expireCounter = new AtomicInteger();
    var reference = new AtomicReference<>();
    Slot slot = new Slot() {
      @Override
      public void release(Poolable obj) {
      }

      @Override
      public void expire(Poolable obj) {
        expireCounter.getAndIncrement();
        reference.set(obj);
      }
    };

    String object = "bla bla";
    var pooled = new Pooled<>(slot, object);
    assertThat(expireCounter.get()).isEqualTo(0);
    assertThat(reference.get()).isNull();
    pooled.expire();
    assertThat(expireCounter.get()).isEqualTo(1);
    assertThat(reference.get()).isSameAs(pooled);
  }
}
