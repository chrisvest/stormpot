package blackbox;

import org.junit.jupiter.api.Test;
import stormpot.Expiration;
import stormpot.GenericPoolable;
import stormpot.MockSlotInfo;

import java.util.concurrent.atomic.AtomicInteger;

import static java.util.concurrent.TimeUnit.SECONDS;
import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class EveryExpirationTest {
  @Test
  void mustResetTimeExpirationOnInvalidation() throws Exception {
    AtomicInteger counter = new AtomicInteger();
    Expiration<GenericPoolable> expiration =
        info -> counter.incrementAndGet() > 0;
    expiration = expiration.every(1, SECONDS);

    MockSlotInfo info = new MockSlotInfo(0);

    assertFalse(expiration.hasExpired(info));
    assertThat(counter.get()).isEqualTo(0);

    assertFalse(expiration.hasExpired(info));
    assertThat(counter.get()).isEqualTo(0);

    info.setAgeInMillis(999);
    assertFalse(expiration.hasExpired(info));
    assertThat(counter.get()).isEqualTo(0);

    info.setAgeInMillis(1000);
    assertTrue(expiration.hasExpired(info));
    assertThat(counter.get()).isEqualTo(1);

    counter.set(0);
    assertFalse(expiration.hasExpired(info));
    assertThat(counter.get()).isEqualTo(0);

    info.setAgeInMillis(1999);
    assertFalse(expiration.hasExpired(info));
    assertThat(counter.get()).isEqualTo(0);

    info.setAgeInMillis(2000);
    assertTrue(expiration.hasExpired(info));
    assertThat(counter.get()).isEqualTo(1);

    counter.set(0);
    assertFalse(expiration.hasExpired(info));
    assertThat(counter.get()).isEqualTo(0);
  }

  @Test
  void mustResetTimeSpreadExpirationOnInvalidation() throws Exception {
    AtomicInteger counter = new AtomicInteger();
    Expiration<GenericPoolable> expiration =
        info -> counter.incrementAndGet() > 0;
    expiration = expiration.every(3, 4, SECONDS);

    MockSlotInfo info = new MockSlotInfo(0);

    assertFalse(expiration.hasExpired(info));
    assertThat(counter.get()).isEqualTo(0);

    assertFalse(expiration.hasExpired(info));
    assertThat(counter.get()).isEqualTo(0);

    info.setAgeInMillis(2999);
    assertFalse(expiration.hasExpired(info));
    assertThat(counter.get()).isEqualTo(0);

    info.setAgeInMillis(4001);
    assertTrue(expiration.hasExpired(info));
    assertThat(counter.get()).isEqualTo(1);

    counter.set(0);
    assertFalse(expiration.hasExpired(info));
    assertThat(counter.get()).isEqualTo(0);

    info.setAgeInMillis(8001);
    assertTrue(expiration.hasExpired(info));
    assertThat(counter.get()).isEqualTo(1);

    counter.set(0);
    assertFalse(expiration.hasExpired(info));
    assertThat(counter.get()).isEqualTo(0);
  }
}
