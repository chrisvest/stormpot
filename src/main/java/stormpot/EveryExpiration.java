package stormpot;

import java.util.concurrent.TimeUnit;

final class EveryExpiration<T extends Poolable> implements Expiration<T> {
  private final Expiration<T> innerExpiration;
  private final TimeSpreadExpiration<T> timeExpiration;

  EveryExpiration(Expiration<T> innerExpiration, long fromTime, long toTime, TimeUnit unit) {
    this.innerExpiration = innerExpiration;
    timeExpiration = new TimeSpreadExpiration<>(fromTime, toTime, unit);
  }

  @Override
  public boolean hasExpired(SlotInfo<? extends T> info) throws Exception {
    if (timeExpiration.hasExpired(info)) {
      // Time-bsaed expiration triggered. Push deadline out.
      info.setStamp(info.getStamp() + timeExpiration.computeExpirationDeadline());
      return innerExpiration.hasExpired(info);
    }
    return false;
  }
}
