package stormpot;

import java.util.concurrent.TimeUnit;

public class TimeSpreadExpiration implements Expiration<Poolable> {

  private final long lowerBoundMillis;
  private final long upperBoundMillis;

  public TimeSpreadExpiration(
      long lowerBound,
      long upperBound,
      TimeUnit unit) {
    if (lowerBound < 1) {
      throw new IllegalArgumentException(
          "The lower bound cannot be less than 1.");
    }
    if (upperBound <= lowerBound) {
      throw new IllegalArgumentException(
          "The upper bound must be greater than the lower bound.");
    }
    if (unit == null) {
      throw new IllegalArgumentException("The TimeUnit cannot be null.");
    }
    this.lowerBoundMillis = unit.toMillis(lowerBound);
    this.upperBoundMillis = unit.toMillis(upperBound);
  }

  @Override
  public boolean hasExpired(SlotInfo<? extends Poolable> info) throws Exception {
    long expirationAge = info.getStamp();
    if (expirationAge == 0) {
      long maxDelta = upperBoundMillis - lowerBoundMillis;
      expirationAge = lowerBoundMillis + Math.abs(info.randomInt() % maxDelta);
      info.setStamp(expirationAge);
    }
    long age = info.getAgeMillis();
    return age >= expirationAge;
  }
}
