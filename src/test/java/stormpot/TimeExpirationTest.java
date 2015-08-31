/*
 * Copyright (C) 2011-2014 Chris Vest (mr.chrisvest@gmail.com)
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *         http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package stormpot;

import org.junit.Test;

import java.util.concurrent.TimeUnit;

import static org.hamcrest.Matchers.is;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertThat;
import static org.junit.Assert.assertTrue;
import static stormpot.MockSlotInfo.mockSlotInfoWithAge;

public class TimeExpirationTest {

  private Expiration<Poolable> createExpiration(int ttl) {
    return new TimeExpiration<>(ttl, TimeUnit.MILLISECONDS);
  }

  @Test(expected = IllegalArgumentException.class) public void
  timeUnitCannotBeNull() {
    new TimeExpiration<>(10, null);
  }
  
  @Test public void
  youngSlotsAreNotInvalid() throws Exception {
    Expiration<Poolable> expiration = createExpiration(2);
    SlotInfo<?> info = mockSlotInfoWithAge(1);
    assertFalse(expiration.hasExpired(info));
  }

  @Test public void
  slotsAtTheMaximumPermittedAgeAreNotInvalid() throws Exception {
    Expiration<Poolable> expiration = createExpiration(2);
    SlotInfo<?> info = mockSlotInfoWithAge(2);
    assertFalse(expiration.hasExpired(info));
  }
  
  @Test public void
  slotsOlderThanTheMaximumPermittedAgeAreInvalid() throws Exception {
    Expiration<Poolable> expiration = createExpiration(2);
    SlotInfo<?> info = mockSlotInfoWithAge(3);
    assertTrue(expiration.hasExpired(info));
  }
  
  @Test(expected = IllegalArgumentException.class) public void
  maxPermittedAgeCannotBeLessThanOne() {
    createExpiration(0);
  }

  @Test public void
  mustHaveNiceToString() {
    TimeExpiration<Poolable> a = new TimeExpiration<>(42, TimeUnit.DAYS);
    assertThat(a.toString(), is("TimeExpiration(42 DAYS)"));

    TimeExpiration<Poolable> b = new TimeExpiration<>(21, TimeUnit.MILLISECONDS);
    assertThat(b.toString(), is("TimeExpiration(21 MILLISECONDS)"));
  }
}
