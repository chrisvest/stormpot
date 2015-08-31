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

import java.util.Iterator;
import java.util.LinkedList;
import java.util.List;
import java.util.concurrent.TimeUnit;

import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.lessThan;
import static org.junit.Assert.assertThat;
import static stormpot.MockSlotInfo.mockSlotInfoWithAge;

public class TimeSpreadExpirationTest {

  @Test(expected = IllegalArgumentException.class) public void
  lowerExpirationBoundCannotBeLessThanOne() {
    createExpiration(0, 2, TimeUnit.NANOSECONDS);
  }
  
  @Test(expected = IllegalArgumentException.class) public void
  upperExpirationBoundMustBeGreaterThanTheLowerBound() {
    createExpiration(1, 1, TimeUnit.NANOSECONDS);
  }
  
  @Test(expected = IllegalArgumentException.class) public void
  timeUnitCannotBeNull() {
    createExpiration(1, 2, null);
  }
  
  @Test public void
  slotsAtExactlyTheUpperExpirationBoundAreAlwaysInvalid() throws Exception {
    TimeSpreadExpiration<Poolable> expiration =
        createExpiration(1, 2, TimeUnit.SECONDS);
    int percentage = expirationPercentage(expiration, 2000);
    assertThat(percentage, is(100));
  }
  
  @Test public void
  slotsYoungerThanTheLowerExpirationBoundAreNeverInvalid() throws Exception {
    TimeSpreadExpiration<Poolable> expiration =
        createExpiration(1, 2, TimeUnit.SECONDS);
    int percentage = expirationPercentage(expiration, 999);
    assertThat(percentage, is(0));
  }
  
  @Test public void
  slotsMidwayInBetweenTheLowerAndUpperBoundHave50PercentChanceOfBeingInvalid()
      throws Exception {
    TimeSpreadExpiration<Poolable> expiration =
        createExpiration(1, 2, TimeUnit.SECONDS);
    int percentage = expirationPercentage(expiration, 1500);
    assertThat(Math.abs(percentage - 50), lessThan(2));
  }
  
  @Test public void
  slotsThatAre25PercentUpTheIntervalHave25PercentChanceOFBeingInvalid()
      throws Exception {
    TimeSpreadExpiration<Poolable> expiration =
        createExpiration(1, 2, TimeUnit.SECONDS);
    int percentage = expirationPercentage(expiration, 1250);
    assertThat(Math.abs(percentage - 25), lessThan(2));
  }
  
  @Test public void
  slotsThatAre75PercentUpTheIntervalHave75PercentChanceOFBeingInvalid()
      throws Exception {
    TimeSpreadExpiration<Poolable> expiration =
        createExpiration(1, 2, TimeUnit.SECONDS);
    int percentage = expirationPercentage(expiration, 1750);
    assertThat(Math.abs(percentage - 75), lessThan(2));
  }

  @Test public void
  mustHaveNiceToString() {
    TimeSpreadExpiration<Poolable> expiration =
        createExpiration(8, 10, TimeUnit.MINUTES);
    assertThat(expiration.toString(),
        is("TimeSpreadExpiration(8 to 10 MINUTES)"));
    expiration = createExpiration(60, 160, TimeUnit.MILLISECONDS);
    assertThat(expiration.toString(),
        is("TimeSpreadExpiration(60 to 160 MILLISECONDS)"));
  }

  @Test public void
  thePercentagesShouldNotChangeNoMatterHowManyTimesAnObjectIsChecked()
      throws Exception {
    int span = 100;
    int base = 1000;
    int top = base + span;
    int objectsPerMillis = 1000;
    int objects = span * objectsPerMillis;
    int expirationCountTollerance = objectsPerMillis / 10;
    int expirationsMin = objectsPerMillis - expirationCountTollerance;
    int expirationsMax = objectsPerMillis + expirationCountTollerance;

    TimeSpreadExpiration<Poolable> expiration =
        createExpiration(base, top, TimeUnit.MILLISECONDS);
    List<MockSlotInfo> infos = new LinkedList<>();

    for (int i = 0; i < objects; i++) {
      infos.add(new MockSlotInfo(base));
    }

    for (int i = 0; i < span; i++) {
      Iterator<MockSlotInfo> itr = infos.iterator();
      int expirations = 0;

      while (itr.hasNext()) {
        MockSlotInfo info = itr.next();
        info.setAgeInMillis(base + i);
        if (expiration.hasExpired(info)) {
          expirations++;
          itr.remove();
        }
      }

      if (expirations < expirationsMin || expirations > expirationsMax) {
        throw new AssertionError(
            "Expected expiration count at millisecond " + i + " to be " +
            "between 900 and 1100, but it was " + expirations);
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

  private TimeSpreadExpiration<Poolable> createExpiration(
      int lowerBound, int upperBound, TimeUnit minutes) {
    return new TimeSpreadExpiration<>(lowerBound, upperBound, minutes);
  }
}
