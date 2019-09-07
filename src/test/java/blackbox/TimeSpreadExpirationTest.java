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
import stormpot.MockSlotInfo;
import stormpot.Poolable;

import java.util.Iterator;
import java.util.LinkedList;
import java.util.List;
import java.util.concurrent.TimeUnit;

import static java.util.concurrent.TimeUnit.*;
import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static stormpot.MockSlotInfo.mockSlotInfoWithAge;

class TimeSpreadExpirationTest {

  @Test
  void lowerExpirationBoundCannotBeLessThanOne() {
    assertThrows(IllegalArgumentException.class, () -> createExpiration(0, 2, TimeUnit.NANOSECONDS));
  }
  
  @Test
  void upperExpirationBoundMustBeGreaterThanTheLowerBound() {
    assertThrows(IllegalArgumentException.class, () -> createExpiration(1, 1, TimeUnit.NANOSECONDS));
  }
  
  @Test
  void timeUnitCannotBeNull() {
    assertThrows(IllegalArgumentException.class, () -> createExpiration(1, 2, null));
  }
  
  @Test
  void slotsAtExactlyTheUpperExpirationBoundAreAlwaysInvalid() throws Exception {
    Expiration<Poolable> expiration = createExpiration(1, 2, SECONDS);
    int percentage = expirationPercentage(expiration, 2000);
    assertThat(percentage).isEqualTo(100);
  }
  
  @Test
  void slotsYoungerThanTheLowerExpirationBoundAreNeverInvalid() throws Exception {
    Expiration<Poolable> expiration = createExpiration(1, 2, SECONDS);
    int percentage = expirationPercentage(expiration, 999);
    assertThat(percentage).isZero();
  }
  
  @Test
  void slotsMidwayInBetweenTheLowerAndUpperBoundHave50PercentChanceOfBeingInvalid()
      throws Exception {
    Expiration<Poolable> expiration = createExpiration(1, 2, SECONDS);
    int percentage = expirationPercentage(expiration, 1500);
    assertThat(Math.abs(percentage - 50)).isLessThan(2);
  }
  
  @Test
  void slotsThatAre25PercentUpTheIntervalHave25PercentChanceOFBeingInvalid()
      throws Exception {
    Expiration<Poolable> expiration = createExpiration(1, 2, SECONDS);
    int percentage = expirationPercentage(expiration, 1250);
    assertThat(Math.abs(percentage - 25)).isLessThan(2);
  }
  
  @Test
  void slotsThatAre75PercentUpTheIntervalHave75PercentChanceOFBeingInvalid()
      throws Exception {
    Expiration<Poolable> expiration = createExpiration(1, 2, SECONDS);
    int percentage = expirationPercentage(expiration, 1750);
    assertThat(Math.abs(percentage - 75)).isLessThan(2);
  }

  @Test
  void mustHaveNiceToString() {
    Expiration<Poolable> expiration = createExpiration(8, 10, MINUTES);
    assertThat(expiration.toString()).isEqualTo("TimeSpreadExpiration(8 to 10 MINUTES)");
    expiration = createExpiration(60, 160, MILLISECONDS);
    assertThat(expiration.toString()).isEqualTo("TimeSpreadExpiration(60 to 160 MILLISECONDS)");
  }

  @Test
  void expirationChancePercentageShouldBeFair() throws Exception {
    int from = 900;
    int to = 1100;
    Expiration<Poolable> expiration = createExpiration(from, to, MILLISECONDS);

    // The age of this object is right in the middle of the range.
    // It should have a 50% chance of expiring.
    MockSlotInfo info = new MockSlotInfo(1000);

    int checks = 10_000;
    int expectedExpirations = checks / 2;
    int tolerance = expectedExpirations / 10;
    int actualExpirations = 0;

    for (int i = 0; i < checks; i++) {
      info.setStamp(0);
      if (expiration.hasExpired(info)) {
        actualExpirations++;
      }
    }

    assertThat(actualExpirations)
        .isGreaterThanOrEqualTo(expectedExpirations - tolerance)
        .isLessThanOrEqualTo(expectedExpirations + tolerance);
  }

  @Test
  void thePercentagesShouldNotChangeNoMatterHowManyTimesAnObjectIsChecked()
      throws Exception {
    int span = 100;
    int from = 1000;
    int to = from + span;
    int objectsPerMillis = 1000;
    int objects = span * objectsPerMillis;
    int expirationCountTolerance = objectsPerMillis / 6;
    int expirationsMin = objectsPerMillis - expirationCountTolerance;
    int expirationsMax = objectsPerMillis + expirationCountTolerance;

    Expiration<Poolable> expiration = createExpiration(from, to, MILLISECONDS);
    List<MockSlotInfo> infos = new LinkedList<>();

    for (int i = 0; i < objects; i++) {
      infos.add(new MockSlotInfo(from));
    }

    for (int i = 0; i < span; i++) {
      Iterator<MockSlotInfo> itr = infos.iterator();
      int expirations = 0;

      while (itr.hasNext()) {
        MockSlotInfo info = itr.next();
        info.setAgeInMillis(from + i);
        if (expiration.hasExpired(info)) {
          expirations++;
          itr.remove();
        }
      }

      if (expirations < expirationsMin || expirations > expirationsMax) {
        throw new AssertionError(
            "Expected expiration count at millisecond " + i + " to be " +
            "between " + expirationsMin + " and " + expirationsMax + ", but it was " + expirations);
      }
    }
  }

  private int expirationPercentage(
      Expiration<Poolable> expiration, long ageInMillis) throws Exception {
    int expired = 0;
    for (int count = 0; count < 100000; count++) {
      MockSlotInfo slotInfo = mockSlotInfoWithAge(ageInMillis);
      if (expiration.hasExpired(slotInfo)) {
        expired++;
      }
    }
    return expired / 1000;
  }

  private Expiration<Poolable> createExpiration(
      int lowerBound, int upperBound, TimeUnit minutes) {
    return Expiration.after(lowerBound, upperBound, minutes);
  }
}
