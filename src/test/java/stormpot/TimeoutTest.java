package stormpot;

import static org.junit.Assert.*;

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
  // TODO equality semantics
}
