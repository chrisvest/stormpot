package stormpot;

/**
 * An informative little interface, used by {@link Expiration}s to
 * determine if a slot has expired or is invalid.
 * @author Chris Vest &lt;mr.chrisvest@gmail.com&gt;
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
   * <p>
   * <strong>Warning:</strong> Do not {@link Poolable#release() release()}
   * Poolables from within an {@link Expiration} &mdash; doing so is a user
   * error, and the behaviour of the pool in such a situation is unspecified
   * and implementation specific. This means that dead-locks and infinite
   * loops are possible outcomes as well.
   * @return The Poolable being examined for validity. Never null.
   */
  T getPoolable();
}
