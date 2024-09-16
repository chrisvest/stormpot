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

import org.junit.jupiter.api.Test;
import stormpot.Completion;
import stormpot.ManagedPool;
import stormpot.Pool;
import stormpot.PoolTap;
import stormpot.Poolable;
import stormpot.Timeout;

import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.jupiter.api.Assertions.assertTrue;

class WhiteboxPoolTest {
  private static final Timeout longTimeout = new Timeout(5, TimeUnit.MINUTES);

  @Test
  void threadSafePoolTapMustDelegateDirectlyToPool() throws Exception {
    AtomicBoolean delegatedToPool = new AtomicBoolean();
    Pool<Poolable> pool = new Pool<>() {
      @Override
      public Completion shutdown() {
        return null;
      }

      @Override
      public void setTargetSize(int size) {
      }

      @Override
      public int getTargetSize() {
        return 0;
      }

      @Override
      public ManagedPool getManagedPool() {
        return null;
      }

      @Override
      public PoolTap<Poolable> getThreadSafeTap() {
        return this::claim;
      }

      @Override
      public PoolTap<Poolable> getVirtualThreadSafeTap() {
        throw new UnsupportedOperationException();
      }

      @Override
      public PoolTap<Poolable> getSingleThreadedTap() {
        throw new UnsupportedOperationException();
      }

      @Override
      public Poolable claim(Timeout timeout) {
        delegatedToPool.set(true);
        return null;
      }

      @Override
      public Poolable tryClaim() {
        return null;
      }
    };
    pool.getThreadSafeTap().claim(longTimeout);
    assertTrue(delegatedToPool.get());
  }
}
