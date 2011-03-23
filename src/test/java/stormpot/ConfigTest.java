package stormpot;

import org.junit.Before;
import org.junit.Test;

public class ConfigTest {
  Config config;
  
  @Before public void
  setUp() {
    config = new Config();
  }
  
  @Test public void
  mustHaveSaneDefaultSize() {
    config.setSize(config.getSize());
  }
  
  @Test(expected = IllegalArgumentException.class) public void
  sizeMustBeAtLeastOne() {
    config.setSize(0);
  }
  
  @Test public void
  goingInsaneMustDisableArgumentChecks() {
    config.goInsane();
    config.setSize(0);
  }
}
