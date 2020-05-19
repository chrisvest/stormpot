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
package stormpot;

import java.util.concurrent.ThreadFactory;

class PoolBuilderDefaults {
  final Expiration<? super Poolable> expiration;
  final ThreadFactory threadFactory;
  final boolean preciseLeakDetectionEnabled;
  final boolean backgroundExpirationEnabled;
  final int backgroundExpirationCheckDelay;

  PoolBuilderDefaults(
      Expiration<Poolable> expiration,
      ThreadFactory threadFactory,
      boolean preciseLeakDetectionEnabled,
      boolean backgroundExpirationEnabled,
      int backgroundExpirationCheckDelay) {
    this.expiration = expiration;
    this.threadFactory = threadFactory;
    this.preciseLeakDetectionEnabled = preciseLeakDetectionEnabled;
    this.backgroundExpirationEnabled = backgroundExpirationEnabled;
    this.backgroundExpirationCheckDelay = backgroundExpirationCheckDelay;
  }
}
