package stormpot;

/**
 * An informative little interface, used by {@link Expiration}s to
 * determine if a slot has expired or is invalid.
 * @author cvh
 * @param <T>
 */
public interface SlotInfo<T extends Poolable> {

  /**
   * Get the approximate number of milliseconds that have transpired since the
   * object was allocated.
   * @return The age of the object in milliseconds.
   */
  long getAgeMillis();

  /**
   * Get the number of times the object has been claimed since it was
   * allocated.
   * @return The objects claim count.
   */
  long getClaimCount();

  /**
   * Get the Poolable object represented by this SlotInfo instance.
   * @return The Poolable being examined for validity. Never null.
   */
  T getPoolable();
}
