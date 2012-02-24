package stormpot;

import static org.junit.Assert.*;

import java.util.concurrent.TimeUnit;

import org.junit.Test;

public class TimeExpirationTest {

  private Expiration<Poolable> createExpiration(int ttl) {
    return new TimeExpiration(ttl, TimeUnit.MILLISECONDS);
  }
  
  private SlotInfo<?> infoWithAge(final long ageMillis) {
    return new SlotInfo<Poolable>() {
      public long getAgeMillis() {
        return ageMillis;
      }

      @Override
      public long getClaimCount() {
        return 0;
      }

      @Override
      public Poolable getPoolable() {
        return null;
      }
    };
  }
  
  @Test(expected = IllegalArgumentException.class) public void
  timeUnitCannotBeNull() {
    new TimeExpiration(10, null);
  }
  
  @Test public void
  youngSlotsAreNotInvalid() {
    Expiration<Poolable> expiration = createExpiration(2);
    SlotInfo<?> info = infoWithAge(1);
    assertFalse(expiration.hasExpired(info));
  }

  @Test public void
  slotsAtTheMaximumPermittedAgeAreNotInvalid() {
    Expiration<Poolable> expiration = createExpiration(2);
    SlotInfo<?> info = infoWithAge(2);
    assertFalse(expiration.hasExpired(info));
  }
  
  @Test public void
  slotsOlderThanTheMaximumPermittedAgeAreInvalid() {
    Expiration<Poolable> expiration = createExpiration(2);
    SlotInfo<?> info = infoWithAge(3);
    assertTrue(expiration.hasExpired(info));
  }
  
  @Test(expected = IllegalArgumentException.class) public void
  maxPermittedAgeCannotBeLessThanOne() {
    createExpiration(0);
  }
}
