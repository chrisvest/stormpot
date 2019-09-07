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
import stormpot.Poolable;
import stormpot.SlotInfo;

import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.*;
import static stormpot.MockSlotInfo.mockSlotInfoWithAge;

class TimeExpirationTest {

  private Expiration<Poolable> createExpiration(int ttl) {
    return Expiration.after(ttl, TimeUnit.MILLISECONDS);
  }

  @Test
  void timeUnitCannotBeNull() {
    assertThrows(IllegalArgumentException.class, () -> Expiration.after(10, null));
  }
  
  @Test
  void youngSlotsAreNotInvalid() throws Exception {
    Expiration<Poolable> expiration = createExpiration(2);
    SlotInfo<?> info = mockSlotInfoWithAge(1);
    assertFalse(expiration.hasExpired(info));
  }

  @Test
  void slotsAtTheMaximumPermittedAgeAreNotInvalid() throws Exception {
    Expiration<Poolable> expiration = createExpiration(2);
    SlotInfo<?> info = mockSlotInfoWithAge(2);
    assertFalse(expiration.hasExpired(info));
  }
  
  @Test
  void slotsOlderThanTheMaximumPermittedAgeAreInvalid() throws Exception {
    Expiration<Poolable> expiration = createExpiration(2);
    SlotInfo<?> info = mockSlotInfoWithAge(3);
    assertTrue(expiration.hasExpired(info));
  }
  
  @Test
  void maxPermittedAgeCannotBeLessThanOne() {
    assertThrows(IllegalArgumentException.class, () -> createExpiration(0));
  }

  @Test
  void mustHaveNiceToString() {
    Expiration<Poolable> a = Expiration.after(42, TimeUnit.DAYS);
    assertThat(a.toString()).isEqualTo("TimeExpiration(42 DAYS)");

    Expiration<Poolable> b = Expiration.after(21, TimeUnit.MILLISECONDS);
    assertThat(b.toString()).isEqualTo("TimeExpiration(21 MILLISECONDS)");
  }
}
