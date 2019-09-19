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

import stormpot.GenericPoolable;
import stormpot.PoolTap;

@SuppressWarnings("unused")
enum Taps {
  POOL {
    @Override
    PoolTap<GenericPoolable> get(PoolTest test) {
      return test.pool;
    }
  },
  THREAD_SAFE {
    @Override
    PoolTap<GenericPoolable> get(PoolTest test) {
      return test.threadSafeTap;
    }
  },
  THREAD_LOCAL {
    @Override
    PoolTap<GenericPoolable> get(PoolTest test) {
      return test.threadLocalTap;
    }
  };

  abstract PoolTap<GenericPoolable> get(PoolTest test);
}
