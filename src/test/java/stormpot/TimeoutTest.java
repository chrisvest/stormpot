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
}
