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

import org.junit.jupiter.api.Test;
import stormpot.Expiration;
import stormpot.GenericPoolable;
import stormpot.MockSlotInfo;

import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static stormpot.ExpireKit.$expired;
import static stormpot.ExpireKit.$fresh;
import static stormpot.ExpireKit.expire;

class OrExpirationTest {

  @Test
  void expiresWhenBothExpirationsExpire() throws Exception {
    Expiration<GenericPoolable> expiration =
        expire($expired).or(expire($expired));

    assertTrue(expiration.hasExpired(mockSlotInfo()));
  }

  @Test
  void expiresWhenOneExpirationExpires() throws Exception {
    Expiration<GenericPoolable> expiration =
        expire($expired).or(expire($fresh));

    assertTrue(expiration.hasExpired(mockSlotInfo()));

    expiration = expire($fresh).or(expire($expired));

    assertTrue(expiration.hasExpired(mockSlotInfo()));
  }

  @Test
  void doesNotExpireWhenNoExpirationExpire() throws Exception {
    Expiration<GenericPoolable> expiration = expire($fresh).or(expire($fresh));

    assertFalse(expiration.hasExpired(mockSlotInfo()));
  }

  @Test
  void mustShortCircuit() throws Exception {
    AtomicBoolean reached = new AtomicBoolean();
    Expiration<GenericPoolable> expiration = expire($expired).or(
        info -> reached.getAndSet(true));

    assertTrue(expiration.hasExpired(mockSlotInfo()));
    assertFalse(reached.get());
  }

  private MockSlotInfo mockSlotInfo() {
    return new MockSlotInfo(0);
  }
}
