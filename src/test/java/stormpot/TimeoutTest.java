package stormpot;

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
}
