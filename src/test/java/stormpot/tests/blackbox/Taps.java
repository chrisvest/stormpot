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
package stormpot.tests.blackbox;

import stormpot.PoolTap;
import stormpot.Poolable;

@SuppressWarnings("unused")
enum Taps {
  POOL {
    @Override
    <T extends Poolable> PoolTap<T> get(AbstractPoolTest<T> test) {
      return test.pool;
    }
  },
  THREAD_SAFE {
    @Override
    <T extends Poolable> PoolTap<T> get(AbstractPoolTest<T> test) {
      return test.threadSafeTap;
    }
  },
  THREAD_VIRTUAL {
    @Override
    <T extends Poolable> PoolTap<T> get(AbstractPoolTest<T> test) {
      return test.threadVirtualTap;
    }
  },
  THREAD_LOCAL {
    @Override
    <T extends Poolable> PoolTap<T> get(AbstractPoolTest<T> test) {
      return test.threadLocalTap;
    }
  };

  abstract <T extends Poolable> PoolTap<T> get(AbstractPoolTest<T> test);
}
