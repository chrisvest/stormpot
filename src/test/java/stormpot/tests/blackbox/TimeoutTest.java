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
package stormpot.tests.blackbox;

import org.junit.jupiter.api.Test;
import stormpot.Timeout;

import java.util.Random;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TimeoutTest {
  @Test
  void timeUnitCannotBeNull() {
    assertThrows(NullPointerException.class, () -> new Timeout(1, null));
  }
  
  @Test
  void timeoutCanBeZeroOrLess() {
    // because it means we don't want to do any waiting at all.
    new Timeout(0, TimeUnit.DAYS);
    new Timeout(-1, TimeUnit.DAYS);
  }

  @Test
  void nonBaseValuesMustBeReproducible() {
    Timeout timeout = new Timeout(13, TimeUnit.MILLISECONDS);
    assertThat(timeout.getTimeout()).isEqualTo(13L);
    assertThat(timeout.getUnit()).isEqualTo(TimeUnit.MILLISECONDS);
  }
  
  @Test
  void baseTimeUnitMustNotBeNull() {
    assertThat(new Timeout(1, TimeUnit.DAYS).getBaseUnit())
        .as("unexpectedly got null for the base unit").isNotNull();
  }
  
  @Test
  void timeoutsWithEqualValueButDifferentUnitsAreEqual() {
    Timeout a = new Timeout(1, TimeUnit.SECONDS);
    Timeout b = new Timeout(1000, TimeUnit.MILLISECONDS);
    assertThat(a).isEqualTo(b);
  }
  
  @Test
  void differentTimeoutValuesAreNotEqual() {
    Timeout a = new Timeout(1, TimeUnit.SECONDS);
    Timeout b =  new Timeout(1, TimeUnit.MILLISECONDS);
    assertThat(a).isNotEqualTo(b);
  }
  
  @Test
  void timeoutsAreNotEqualToObjectsOfDifferentClasses() {
    Object a = new Timeout(1, TimeUnit.SECONDS);
    Object b = "poke";
    assertThat(a).isNotEqualTo(b);
  }
  
  @Test
  void timeoutsAreNotEqualToNull() {
    Timeout a = new Timeout(1, TimeUnit.SECONDS);
    //noinspection SimplifiableJUnitAssertion,ConstantConditions
    assertFalse(a.equals(null));
  }
  
  @SuppressWarnings("EqualsWithItself")
  @Test
  void timeoutsAreEqualToThemselves() {
    Timeout a = new Timeout(1, TimeUnit.SECONDS);
    assertThat(a).isEqualTo(a);
  }
  
  @Test
  void sameValueAndUnitGivesSameHashCode() {
    Timeout a = new Timeout(1, TimeUnit.SECONDS);
    Timeout b = new Timeout(1, TimeUnit.SECONDS);
    assertEquals(a.hashCode(), b.hashCode());
  }
  
  @Test
  void differentValueGivesDifferentHashcode() {
    Timeout a = new Timeout(1, TimeUnit.SECONDS);
    Timeout b = new Timeout(2, TimeUnit.SECONDS);
    assertTrue(a.hashCode() != b.hashCode());
  }
  
  @Test
  void differentUnitsForSameValuesGivesSameHashcode() {
    Timeout a = new Timeout(1, TimeUnit.SECONDS);
    Timeout b = new Timeout(1000, TimeUnit.MILLISECONDS);
    assertEquals(a.hashCode(), b.hashCode());
  }
  
  @Test
  void sameValuesOfDifferentUnitsGivesDifferentHashcode() {
    Timeout a = new Timeout(1, TimeUnit.SECONDS);
    Timeout b = new Timeout(1, TimeUnit.MILLISECONDS);
    assertTrue(a.hashCode() != b.hashCode());
  }

  @Test
  void canGetTimeoutInBaseUnit() {
    Timeout a = new Timeout(1, TimeUnit.SECONDS);
    Timeout b = new Timeout(1000, TimeUnit.MILLISECONDS);
    long timeoutBaseA = a.getTimeoutInBaseUnit();
    long timeoutBaseB = b.getTimeoutInBaseUnit();
    assertThat(timeoutBaseA).isEqualTo(timeoutBaseB);
  }

  @Test
  void timeoutValueMustConvertExactlyToBaseUnit() {
    TimeUnit seconds = TimeUnit.SECONDS;
    int value = 1;
    Timeout timeout = new Timeout(value, seconds);
    long convertedValue = timeout.getBaseUnit().convert(value, seconds);
    assertThat(timeout.getTimeoutInBaseUnit()).isEqualTo(convertedValue);
  }

  @Test
  void specifyingTimeoutInBaseUnitMustDoNoConversion() {
    Timeout a = new Timeout(1, TimeUnit.SECONDS);
    long baseTimeout = a.getTimeoutInBaseUnit();
    Timeout b = new Timeout(baseTimeout, a.getBaseUnit());
    assertThat(b.getTimeoutInBaseUnit()).isEqualTo(b.getTimeout());
  }

  @Test
  void equalTimeoutsMustHaveSameHashCode() {
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
        assertThat(timeoutA).isEqualTo(timeoutB);
      }
    }
  }
}
