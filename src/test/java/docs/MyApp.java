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
package docs;

import stormpot.Pool;
import stormpot.Timeout;

import java.util.concurrent.TimeUnit;

public class MyApp {
  @SuppressWarnings("EmptyTryBlock")
  public static void main(String[] args) throws InterruptedException {
    // tag::usageMyApp[]
    Pool<MyPoolable> pool = Pool.from(new MyAllocator()).build();
    Timeout timeout = new Timeout(1, TimeUnit.SECONDS);

    MyPoolable object = pool.claim(timeout);
    try {
      // Do stuff with 'object'.
      // Note that 'claim' will return 'null' if it times out!
    } finally {
      if (object != null) {
        object.release();
      }
    }
    // end::usageMyApp[]
  }
}
