package stormpot;

/**
 * The PoolException may be thrown by a pool implementation in a number of
 * circumstances:
 * <ul>
 * <li>If claim is called and the pool needs to
 * {@link Allocator#allocate(Slot) allocate} a new object, but the allocation
 * fails by returning <code>null</code> or throwing an exception.
 * <li>If the {@link Slot#release(Poolable)} method is misused, and the pool is
 * able to detect this.
 * </ul>
 * @author Chris Vest &lt;mr.chrisvest@gmail.com&gt;
 *
 */
public class PoolException extends RuntimeException {
  private static final long serialVersionUID = -1908093409167496640L;

  /**
   * Construct a new PoolException with the given message.
   * @param message A description of the exception to be returned from
   * {@link #getMessage()}.
   * @see RuntimeException#RuntimeException(String)
   */
  public PoolException(String message) {
    super(message);
  }

  /**
   * Construct a new PoolException with the given message and cause.
   * @param message A description fo the exception to be returned form
   * {@link #getMessage()}.
   * @param cause The underlying cause of this exception, as to be shown in the
   * stack trace, and available through {@link #getCause()}.
   * @see RuntimeException#RuntimeException(String, Throwable)
   */
  public PoolException(String message, Throwable cause) {
    super(message, cause);
  }
}
