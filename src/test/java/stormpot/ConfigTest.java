package stormpot;

import static org.junit.Assert.*;
import static org.hamcrest.Matchers.*;

import java.util.concurrent.TimeUnit;

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
    config.setTTL(config.getTTL(), config.getTTLUnit());
  }
  
  @Test(expected = IllegalArgumentException.class) public void
  sizeMustBeAtLeastOne() {
    config.setSize(0);
  }
  
  @Test(expected = IllegalArgumentException.class) public void
  ttlUnitCannotBeNull() {
    config.setTTL(1, null);
  }
  
  @Test public void
  ttlMustBeSettable() {
    long ttl = config.getTTL() + 1;
    assertThat(config.setTTL(ttl, config.getTTLUnit()).getTTL(), is(ttl));
  }
  
  @Test public void
  ttlUnitMustBeSettable() {
    TimeUnit unit = TimeUnit.DAYS;
    assertThat(config.setTTL(1, unit).getTTLUnit(), is(unit));
  }
  
  @Test public void
  goingInsaneMustDisableArgumentChecks() {
    config.goInsane();
    config.setSize(0);
    config.setTTL(1, null);
  }
}
