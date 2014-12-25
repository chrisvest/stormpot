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

import java.util.Random;
import java.util.concurrent.TimeUnit;

import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.not;
import static org.junit.Assert.*;

public class TimeoutTest {
  @Test(expected = IllegalArgumentException.class) public void
  timeUnitCannotBeNull() {
    new Timeout(1, null);
  }
  
  @Test public void
  timeoutCanBeZeroOrLess() {
    // because it means we don't want to do any waiting at all.
    new Timeout(0, TimeUnit.DAYS);
    new Timeout(-1, TimeUnit.DAYS);
  }

  @Test public void
  nonBaseValuesMustBeReproducible() {
    Timeout timeout = new Timeout(13, TimeUnit.MILLISECONDS);
    assertThat(timeout.getTimeout(), is(13L));
    assertThat(timeout.getUnit(), is(TimeUnit.MILLISECONDS));
  }
  
  @Test public void
  baseTimeUnitMustNotBeNull() {
    assertNotNull("unexpectedly got null for the base unit",
        new Timeout(1, TimeUnit.DAYS).getBaseUnit());
  }
  
  @Test public void
  timeoutsWithEqualValueButDifferentUnitsAreEqual() {
    Timeout a = new Timeout(1, TimeUnit.SECONDS);
    Timeout b = new Timeout(1000, TimeUnit.MILLISECONDS);
    assertThat(a, equalTo(b));
  }
  
  @Test public void
  differentTimeoutValuesAreNotEqual() {
    Timeout a = new Timeout(1, TimeUnit.SECONDS);
    Timeout b =  new Timeout(1, TimeUnit.MILLISECONDS);
    assertThat(a, not(equalTo(b)));
  }
  
  @Test public void
  timeoutsAreNotEqualToObjectsOfDifferentClasses() {
    Object a = new Timeout(1, TimeUnit.SECONDS);
    Object b = "poke";
    assertThat(a, not(equalTo(b)));
  }
  
  @Test public void
  timeoutsAreNotEqualToNull() {
    Timeout a = new Timeout(1, TimeUnit.SECONDS);
    //noinspection ObjectEqualsNull
    assertFalse(a.equals(null));
  }
  
  @Test public void
  timeoutsAreEqualToThemselves() {
    Timeout a = new Timeout(1, TimeUnit.SECONDS);
    assertThat(a, equalTo(a));
  }
  
  @Test public void
  sameValueAndUnitGivesSameHashCode() {
    Timeout a = new Timeout(1, TimeUnit.SECONDS);
    Timeout b = new Timeout(1, TimeUnit.SECONDS);
    assertTrue(a.hashCode() == b.hashCode());
  }
  
  @Test public void
  differentValueGivesDifferentHashcode() {
    Timeout a = new Timeout(1, TimeUnit.SECONDS);
    Timeout b = new Timeout(2, TimeUnit.SECONDS);
    assertTrue(a.hashCode() != b.hashCode());
  }
  
  @Test public void
  differentUnitsForSameValuesGivesSameHashcode() {
    Timeout a = new Timeout(1, TimeUnit.SECONDS);
    Timeout b = new Timeout(1000, TimeUnit.MILLISECONDS);
    assertTrue(a.hashCode() == b.hashCode());
  }
  
  @Test public void
  sameValuesOfDifferentUnitsGivesDifferentHashcode() {
    Timeout a = new Timeout(1, TimeUnit.SECONDS);
    Timeout b = new Timeout(1, TimeUnit.MILLISECONDS);
    assertTrue(a.hashCode() != b.hashCode());
  }

  @Test public void
  canGetTimeoutInBaseUnit() {
    Timeout a = new Timeout(1, TimeUnit.SECONDS);
    Timeout b = new Timeout(1000, TimeUnit.MILLISECONDS);
    long timeoutBaseA = a.getTimeoutInBaseUnit();
    long timeoutBaseB = b.getTimeoutInBaseUnit();
    assertTrue(timeoutBaseA == timeoutBaseB);
  }

  @Test public void
  timeoutValueMustConvertExactlyToBaseUnit() {
    TimeUnit seconds = TimeUnit.SECONDS;
    int value = 1;
    Timeout timeout = new Timeout(value, seconds);
    long convertedValue = timeout.getBaseUnit().convert(value, seconds);
    assertThat(timeout.getTimeoutInBaseUnit(), is(convertedValue));
  }

  @Test public void
  specifyingTimeoutInBaseUnitMustDoNoConversion() {
    Timeout a = new Timeout(1, TimeUnit.SECONDS);
    long baseTimeout = a.getTimeoutInBaseUnit();
    Timeout b = new Timeout(baseTimeout, a.getBaseUnit());
    assertThat(b.getTimeoutInBaseUnit(), is(b.getTimeout()));
  }

  @Test public void
  equalTimeoutsMustHaveSameHashCode() {
    Random rng = new Random();
    TimeUnit[] units = TimeUnit.values();
    int[] magnitudes = new int[] {1, 24, 60, 1000};

    for (int i = 0; i < 1000000; i++) {
      TimeUnit unitA = units[rng.nextInt(units.length)];
      TimeUnit unitB = units[rng.nextInt(units.length)];
      int magnitudeA = magnitudes[rng.nextInt(magnitudes.length)];
      int magnitudeB = magnitudes[rng.nextInt(magnitudes.length)];
      Timeout timeoutA = new Timeout(magnitudeA, unitA);
      Timeout timeoutB = new Timeout(magnitudeB, unitB);

      if (timeoutA.equals(timeoutB)) {
        assertThat(timeoutA + " = " + timeoutB, timeoutA, equalTo(timeoutB));
      }
    }
  }
}
