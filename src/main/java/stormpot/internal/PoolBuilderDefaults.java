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
package stormpot.internal;

import stormpot.Expiration;
import stormpot.Poolable;

import java.util.concurrent.ThreadFactory;

public final class PoolBuilderDefaults {
  public final Expiration<? super Poolable> expiration;
  public final ThreadFactory threadFactory;
  public final boolean preciseLeakDetectionEnabled;
  public final boolean backgroundExpirationEnabled;
  public final int backgroundExpirationCheckDelay;

  public PoolBuilderDefaults(
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
