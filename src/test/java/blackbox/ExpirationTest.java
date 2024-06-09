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
package blackbox;

import org.junit.jupiter.api.Test;
import stormpot.Expiration;
import stormpot.GenericPoolable;
import stormpot.Poolable;
import stormpot.SlotInfo;
import stormpot.SlotInfoStub;

import java.util.Iterator;
import java.util.LinkedList;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import static java.util.concurrent.TimeUnit.MILLISECONDS;
import static java.util.concurrent.TimeUnit.MINUTES;
import static java.util.concurrent.TimeUnit.SECONDS;
import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static stormpot.ExpireKit.$expired;
import static stormpot.ExpireKit.$fresh;
import static stormpot.ExpireKit.expire;

class ExpirationTest {
  @Test
  void timeUnitCannotBeNull() {
    assertThrows(NullPointerException.class, () -> Expiration.after(10, null));
    assertThrows(NullPointerException.class, () -> Expiration.after(1, 2, null));
  }

  @Test
  void youngSlotsAreNotInvalid() throws Exception {
    Expiration<Poolable> expiration = Expiration.after(2, MILLISECONDS);
    SlotInfo<?> info = new SlotInfoStub(1);
    assertFalse(expiration.hasExpired(info));
  }

  @Test
  void slotsAtTheMaximumPermittedAgeAreNotInvalid() throws Exception {
    Expiration<Poolable> expiration = Expiration.after(2, MILLISECONDS);
    SlotInfo<?> info = new SlotInfoStub(2);
    assertFalse(expiration.hasExpired(info));
  }

  @Test
  void slotsOlderThanTheMaximumPermittedAgeAreInvalid() throws Exception {
    Expiration<Poolable> expiration = Expiration.after(2, MILLISECONDS);
    SlotInfo<?> info = new SlotInfoStub(3);
    assertTrue(expiration.hasExpired(info));
  }

  @Test
  void maxPermittedAgeCannotBeLessThanOne() {
    assertThrows(IllegalArgumentException.class, () -> Expiration.after(0, MILLISECONDS));
  }

  @Test
  void mustHaveNiceToString() {
    Expiration<Poolable> a = Expiration.after(42, TimeUnit.DAYS);
    assertThat(a.toString()).isEqualTo("TimeExpiration(42 DAYS)");

    Expiration<Poolable> b = Expiration.after(21, MILLISECONDS);
    assertThat(b.toString()).isEqualTo("TimeExpiration(21 MILLISECONDS)");

    Expiration<Poolable> expiration = Expiration.after(8, 10, MINUTES);
    assertThat(expiration.toString()).isEqualTo("TimeSpreadExpiration(8 to 10 MINUTES)");
    expiration = Expiration.after(60, 160, MILLISECONDS);
    assertThat(expiration.toString()).isEqualTo("TimeSpreadExpiration(60 to 160 MILLISECONDS)");
  }

  @Test
  void lowerExpirationBoundCannotBeLessThanOne() {
    assertThrows(IllegalArgumentException.class,
        () -> Expiration.after(0, 2, TimeUnit.NANOSECONDS));
  }

  @Test
  void upperExpirationBoundMustBeGreaterThanOrEqualToTheLowerBound() {
    assertThrows(IllegalArgumentException.class,
        () -> Expiration.after(100, 99, TimeUnit.NANOSECONDS));
  }

  @Test
  void lowerAndUpperBoundCanBeEqual() throws Exception {
    Expiration<Poolable> expiration = Expiration.after(1, 1, SECONDS);
    assertThat(expirationPercentage(expiration, 999)).isEqualTo(0);
    assertThat(expirationPercentage(expiration, 1000)).isEqualTo(100);
    assertThat(expirationPercentage(expiration, 1001)).isEqualTo(100);
  }

  @Test
  void slotsAtExactlyTheUpperExpirationBoundAreAlwaysInvalid() throws Exception {
    Expiration<Poolable> expiration = Expiration.after(1, 2, SECONDS);
    int percentage = expirationPercentage(expiration, 2000);
    assertThat(percentage).isEqualTo(100);
  }

  @Test
  void slotsYoungerThanTheLowerExpirationBoundAreNeverInvalid() throws Exception {
    Expiration<Poolable> expiration = Expiration.after(1, 2, SECONDS);
    int percentage = expirationPercentage(expiration, 999);
    assertThat(percentage).isZero();
  }

  @Test
  void slotsMidwayInBetweenTheLowerAndUpperBoundHave50PercentChanceOfBeingInvalid()
      throws Exception {
    Expiration<Poolable> expiration = Expiration.after(1, 2, SECONDS);
    int percentage = expirationPercentage(expiration, 1500);
    assertThat(Math.abs(percentage - 50)).isLessThan(2);
  }

  @Test
  void slotsThatAre25PercentUpTheIntervalHave25PercentChanceOFBeingInvalid()
      throws Exception {
    Expiration<Poolable> expiration = Expiration.after(1, 2, SECONDS);
    int percentage = expirationPercentage(expiration, 1250);
    assertThat(Math.abs(percentage - 25)).isLessThan(2);
  }

  @Test
  void slotsThatAre75PercentUpTheIntervalHave75PercentChanceOFBeingInvalid()
      throws Exception {
    Expiration<Poolable> expiration = Expiration.after(1, 2, SECONDS);
    int percentage = expirationPercentage(expiration, 1750);
    assertThat(Math.abs(percentage - 75)).isLessThan(2);
  }

  @Test
  void expirationChancePercentageShouldBeFair() throws Exception {
    int from = 900;
    int to = 1100;
    Expiration<Poolable> expiration = Expiration.after(from, to, MILLISECONDS);

    // The age of this object is right in the middle of the range.
    // It should have a 50% chance of expiring.
    SlotInfoStub info = new SlotInfoStub(1000);

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

    Expiration<Poolable> expiration = Expiration.after(from, to, MILLISECONDS);
    List<SlotInfoStub> infos = new LinkedList<>();

    for (int i = 0; i < objects; i++) {
      infos.add(new SlotInfoStub(from));
    }

    for (int i = 0; i < span; i++) {
      Iterator<SlotInfoStub> itr = infos.iterator();
      int expirations = 0;

      while (itr.hasNext()) {
        SlotInfoStub info = itr.next();
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
      SlotInfoStub slotInfo = new SlotInfoStub(ageInMillis);
      if (expiration.hasExpired(slotInfo)) {
        expired++;
      }
    }
    return expired / 1000;
  }

  @Test
  void expiresWhenBothExpirationsExpire() throws Exception {
    Expiration<GenericPoolable> expiration =
        expire($expired).or(expire($expired));

    assertTrue(expiration.hasExpired(new SlotInfoStub()));
  }

  @Test
  void expiresWhenOneExpirationExpires() throws Exception {
    Expiration<GenericPoolable> expiration =
        expire($expired).or(expire($fresh));

    assertTrue(expiration.hasExpired(new SlotInfoStub()));

    expiration = expire($fresh).or(expire($expired));

    assertTrue(expiration.hasExpired(new SlotInfoStub()));
  }

  @Test
  void doesNotExpireWhenNoExpirationExpire() throws Exception {
    Expiration<GenericPoolable> expiration = expire($fresh).or(expire($fresh));

    assertFalse(expiration.hasExpired(new SlotInfoStub()));
  }

  @Test
  void mustShortCircuit() throws Exception {
    AtomicBoolean reached = new AtomicBoolean();
    Expiration<GenericPoolable> expiration = expire($expired).or(
        info -> reached.getAndSet(true));

    assertTrue(expiration.hasExpired(new SlotInfoStub()));
    assertFalse(reached.get());
  }

  @Test
  void mustResetTimeExpirationOnInvalidation() throws Exception {
    AtomicInteger counter = new AtomicInteger();
    Expiration<GenericPoolable> expiration =
        info -> counter.incrementAndGet() > 0;
    expiration = expiration.every(1, SECONDS);

    SlotInfoStub info = new SlotInfoStub(0);

    assertFalse(expiration.hasExpired(info));
    assertThat(counter.get()).isEqualTo(0);

    assertFalse(expiration.hasExpired(info));
    assertThat(counter.get()).isEqualTo(0);

    info.setAgeInMillis(999);
    assertFalse(expiration.hasExpired(info));
    assertThat(counter.get()).isEqualTo(0);

    info.setAgeInMillis(1000);
    assertTrue(expiration.hasExpired(info));
    assertThat(counter.get()).isEqualTo(1);

    counter.set(0);
    assertFalse(expiration.hasExpired(info));
    assertThat(counter.get()).isEqualTo(0);

    info.setAgeInMillis(1999);
    assertFalse(expiration.hasExpired(info));
    assertThat(counter.get()).isEqualTo(0);

    info.setAgeInMillis(2000);
    assertTrue(expiration.hasExpired(info));
    assertThat(counter.get()).isEqualTo(1);

    counter.set(0);
    assertFalse(expiration.hasExpired(info));
    assertThat(counter.get()).isEqualTo(0);
  }

  @Test
  void mustResetTimeSpreadExpirationOnInvalidation() throws Exception {
    AtomicInteger counter = new AtomicInteger();
    Expiration<GenericPoolable> expiration =
        info -> counter.incrementAndGet() > 0;
    expiration = expiration.every(3, 4, SECONDS);

    SlotInfoStub info = new SlotInfoStub(0);

    assertFalse(expiration.hasExpired(info));
    assertThat(counter.get()).isEqualTo(0);

    assertFalse(expiration.hasExpired(info));
    assertThat(counter.get()).isEqualTo(0);

    info.setAgeInMillis(2999);
    assertFalse(expiration.hasExpired(info));
    assertThat(counter.get()).isEqualTo(0);

    info.setAgeInMillis(4001);
    assertTrue(expiration.hasExpired(info));
    assertThat(counter.get()).isEqualTo(1);

    counter.set(0);
    assertFalse(expiration.hasExpired(info));
    assertThat(counter.get()).isEqualTo(0);

    info.setAgeInMillis(8001);
    assertTrue(expiration.hasExpired(info));
    assertThat(counter.get()).isEqualTo(1);

    counter.set(0);
    assertFalse(expiration.hasExpired(info));
    assertThat(counter.get()).isEqualTo(0);
  }

  @Test
  void neverExpirationNeverInvalidatesSlots() throws Exception {
    Expiration<GenericPoolable> never = Expiration.never();
    SlotInfoStub info = new SlotInfoStub(0);
    assertFalse(never.hasExpired(info));
  }
}
