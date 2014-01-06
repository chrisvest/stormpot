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

import static org.hamcrest.Matchers.*;
import static org.junit.Assert.*;

import java.util.Iterator;
import java.util.LinkedList;
import java.util.List;
import java.util.concurrent.TimeUnit;

import org.junit.Test;

public class TimeSpreadExpirationTest {

  @Test(expected = IllegalArgumentException.class) public void
  lowerExpirationBoundCannotBeLessThanOne() {
    new TimeSpreadExpiration(0, 2, TimeUnit.NANOSECONDS);
  }
  
  @Test(expected = IllegalArgumentException.class) public void
  upperExpirationBoundMustBeGreaterThanTheLowerBound() {
    new TimeSpreadExpiration(1, 1, TimeUnit.NANOSECONDS);
  }
  
  @Test(expected = IllegalArgumentException.class) public void
  timeUnitCannotBeNull() {
    new TimeSpreadExpiration(1, 2, null);
  }
  
  @Test public void
  slotsAtExactlyTheUpperExpirationBoundAreAlwaysInvalid() throws Exception {
    TimeSpreadExpiration expiration =
        new TimeSpreadExpiration(1, 2, TimeUnit.SECONDS);
    int percentage = expirationPercentage(expiration, 2000);
    assertThat(percentage, is(100));
  }
  
  @Test public void
  slotsYoungerThanTheLowerExpirationBoundAreNeverInvalid() throws Exception {
    TimeSpreadExpiration expiration =
        new TimeSpreadExpiration(1, 2, TimeUnit.SECONDS);
    int percentage = expirationPercentage(expiration, 999);
    assertThat(percentage, is(0));
  }
  
  @Test public void
  slotsMidwayInBetweenTheLowerAndUpperBoundHave50PercentChanceOfBeingInvalid()
      throws Exception {
    TimeSpreadExpiration expiration =
        new TimeSpreadExpiration(1, 2, TimeUnit.SECONDS);
    int percentage = expirationPercentage(expiration, 1500);
    assertThat(Math.abs(percentage - 50), lessThan(2));
  }
  
  @Test public void
  slotsThatAre25PercentUpTheIntervalHave25PercentChanceOFBeingInvalid()
      throws Exception {
    TimeSpreadExpiration expiration =
        new TimeSpreadExpiration(1, 2, TimeUnit.SECONDS);
    int percentage = expirationPercentage(expiration, 1250);
    assertThat(Math.abs(percentage - 25), lessThan(2));
  }
  
  @Test public void
  slotsThatAre75PercentUpTheIntervalHave75PercentChanceOFBeingInvalid()
      throws Exception {
    TimeSpreadExpiration expiration =
        new TimeSpreadExpiration(1, 2, TimeUnit.SECONDS);
    int percentage = expirationPercentage(expiration, 1750);
    assertThat(Math.abs(percentage - 75), lessThan(2));
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
    
    TimeSpreadExpiration expiration =
        new TimeSpreadExpiration(base, top, TimeUnit.MILLISECONDS);
    List<MockSlotInfo> infos = new LinkedList<MockSlotInfo>();
    
    for (int i = 0; i < objects; i++) {
      infos.add(new MockSlotInfo(base));
    }
    
    for (int i = 0; i < span; i++) {
      Iterator<MockSlotInfo> itr = infos.iterator();
      int expirations = 0;
      
      while (itr.hasNext()) {
        MockSlotInfo info = itr.next();
        info.ageInMillis = base + i;
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
      MockSlotInfo slotInfo = new MockSlotInfo(ageInMillis);
      if (expiration.hasExpired(slotInfo)) {
        expired++;
      }
    }
    return expired / 1000;
  }
  
  private static final class MockSlotInfo implements SlotInfo<Poolable> {
    private static int seed = 987623458;
    
    private static int xorShift(int seed) {
      seed ^= (seed << 6);
      seed ^= (seed >>> 21);
      return seed ^ (seed << 7);
    }
    
    long ageInMillis;
    private long stamp = 0;

    private MockSlotInfo(long ageInMillis) {
      this.ageInMillis = ageInMillis;
    }

    @Override
    public long getAgeMillis() {
      return ageInMillis;
    }

    @Override
    public long getClaimCount() {
      return 0;
    }

    @Override
    public Poolable getPoolable() {
      return null;
    }

    @Override
    public int randomInt() {
      return seed = xorShift(seed);
    }

    @Override
    public long getStamp() {
      return stamp;
    }

    @Override
    public void setStamp(long stamp) {
      this.stamp = stamp;
    }
  }
}
