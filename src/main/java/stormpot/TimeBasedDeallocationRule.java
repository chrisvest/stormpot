package stormpot;

import java.util.concurrent.TimeUnit;

public class TimeBasedDeallocationRule implements DeallocationRule {

  private final long maxPermittedAgeMillis;

  public TimeBasedDeallocationRule(long maxPermittedAge, TimeUnit unit) {
    if (maxPermittedAge < 1) {
      throw new IllegalArgumentException(
          "max permitted age cannot be less than 1");
    }
    if (unit == null) {
      throw new IllegalArgumentException("unit cannot be null");
    }
    maxPermittedAgeMillis = unit.toMillis(maxPermittedAge);
  }

  public <T extends Poolable> boolean isInvalid(SlotInfo<T> info) {
    return info.getAgeMillis() > maxPermittedAgeMillis;
  }
}
