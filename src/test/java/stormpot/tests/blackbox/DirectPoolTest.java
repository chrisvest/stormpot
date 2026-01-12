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
import stormpot.Allocator;
import stormpot.ManagedPool;
import stormpot.Pool;
import stormpot.Pooled;
import stormpot.Slot;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

class DirectPoolTest extends AbstractPoolTest<Pooled<String>> {
  private void createPool(String... objects) {
    pool = Pool.of(objects);
    threadSafeTap = pool.getThreadSafeTap();
    threadVirtualTap = pool.getVirtualThreadSafeTap();
    threadLocalTap = pool.getSingleThreadedTap();
  }

  @Override
  void createOneObjectPool() {
    createPoolOfSize(1);
  }

  @Override
  void noBackgroundExpirationChecking() {
  }

  @Override
  void createPoolOfSize(int size) {
    assert size < 25;
    String[] objs = new String[size];
    for (int i = 0; i < size; i++) {
      objs[i] = String.valueOf((char) ('a' + i));
    }
    createPool(objs);
  }

  @Test
  void managedPoolLeakedObjectCountIsNotSupported() {
    createOneObjectPool();
    ManagedPool managedPool = pool.getManagedPool();
    assertThat(managedPool.getLeakedObjectsCount()).isEqualTo(-1);
  }

  @Test
  void changingTargetSizeIsNotSupported() {
    createOneObjectPool();
    assertThrows(UnsupportedOperationException.class, () -> pool.setTargetSize(1));
  }

  @Test
  void pooledIsAutoCloseable() throws Exception {
    createOneObjectPool();
    try (Pooled<String> pooled = pool.claim(longTimeout)) {
      assertThat(pooled.object).isEqualTo("a");
      assertFalse(pool.supply(shortTimeout,
          object -> fail("Did not expect to claim object: " + object)));
    }
    assertTrue(pool.supply(shortTimeout, object -> {}));
  }

  @Test
  void explicitlyExpiredObjectsReturnToThePool() throws Exception {
    createOneObjectPool();
    String claimedObject;
    try (Pooled<String> object = pool.claim(longTimeout)) {
      object.expire();
      claimedObject = object.object;
    }
    try (Pooled<String> object = pool.claim(longTimeout)) {
      assertThat(object.object).isSameAs(claimedObject);
    }
  }

  @Test
  void mustThrowWhenSwitchingAllocator() {
    createOneObjectPool();
    Allocator<Pooled<String>> newAllocator = new Allocator<>() {
      @Override
      public Pooled<String> allocate(Slot slot) {
        return fail("Not meant to be called.");
      }

      @Override
      public void deallocate(Pooled<String> poolable) {
        fail("Not meant to be called.");
      }
    };
    assertThrows(UnsupportedOperationException.class, () -> pool.switchAllocator(newAllocator));
  }
}
