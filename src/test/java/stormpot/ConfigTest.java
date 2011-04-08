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
    config.setSize(0);
    config.setTTL(1, null);
  }
}
