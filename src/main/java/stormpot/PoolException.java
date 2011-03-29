package stormpot;

/**
 * The PoolException may be thrown by a pool implementation in a number of
 * circumstances:
 * <ul>
 * <li>If claim is called and the pool needs to
 * {@link Allocator#allocate(Slot) allocate} a new object, but the allocation
 * fails by returning <code>null</code> or throwing an exception.
 * <li>If the {@link Slot#release()} method is misused, and the pool is able
 * to detect this.
 * </ul>
 * @author Chris Vest &lt;mr.chrisvest@gmail.com&gt;
 *
 */
public class PoolException extends RuntimeException {
  private static final long serialVersionUID = -1908093409167496640L;

  public PoolException() {
  }

  public PoolException(String message) {
    super(message);
  }

  public PoolException(Throwable cause) {
    super(cause);
  }

  public PoolException(String message, Throwable cause) {
    super(message, cause);
  }
}
