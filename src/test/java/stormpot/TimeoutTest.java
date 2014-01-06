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

import static org.junit.Assert.*;
import static org.hamcrest.Matchers.*;

import java.util.concurrent.TimeUnit;

import org.junit.Test;

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
}
