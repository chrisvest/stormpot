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
package blackbox;

import org.junit.jupiter.api.Test;
import testkits.AlloKit.CountingAllocator;
import testkits.GenericPoolable;
import stormpot.Pool;
import stormpot.PoolBuilder;

import static org.junit.jupiter.api.Assertions.assertThrows;

class InlinePoolTest extends AllocatorBasedPoolTest {
  @Override
  protected PoolBuilder<GenericPoolable> createInitialPoolBuilder(CountingAllocator allocator) {
    return Pool.fromInline(allocator);
  }

  @Test
  void constructorMustThrowWhenSettingBackgroundExpirationCheckDelay() {
    assertThrows(IllegalStateException.class, () -> builder.setBackgroundExpirationCheckDelay(1));
    assertThrows(IllegalStateException.class, () -> builder.setBackgroundExpirationCheckDelay(0));
    assertThrows(IllegalStateException.class, () -> builder.setBackgroundExpirationCheckDelay(-1));
  }

  @Test
  void constructorMustThrowWhenSettingThreadFactory() {
    assertThrows(IllegalStateException.class, () -> builder.setThreadFactory(null));
    assertThrows(IllegalStateException.class, () -> builder.setThreadFactory(r -> null));
    assertThrows(IllegalStateException.class, () -> builder.setThreadFactory(Thread::new));
  }
}
