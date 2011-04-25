package stormpot;

/**
 * Objects contained in a {@link Pool} must implement the Poolable interface
 * and adhere to its contract.
 * <p>
 * Pools call {@link Allocator#allocate(Slot) allocate} on Allocators with the
 * specific {@link Slot} that they want a Poolable allocated for. The Slot
 * represents a location in the pool, that can fit an object and make it
 * available for others to claim.
 * <p>
 * The contract of the Poolable interface is, that when {@link #release()
 * release} is called on the Poolable, it must in turn call
 * {@link Slot#release(Poolable) release} on the specific Slot object that it
 * was allocated with, giving itself as the Poolable parameter.
 * <p>
 * The simplest possible correct implementation of the Poolable interface looks
 * like this:
 * <pre><code> public class GenericPoolable implements Poolable {
 *   private final Slot slot;
 *   public GenericPoolable(Slot slot) {
 *     this.slot = slot;
 *   }
 *   
 *   public void release() {
 *     slot.release(this);
 *   }
 * }</code></pre>
 * Pools in turn guarantee these memory effects:
 * <ul>
 * <li>The {@link Poolable#release() release} of an object happens-before
 * any subsequent claim of that object, and,
 * <li>The {@link Allocator#allocate(Slot) allocation} of an object
 * happens-before any claim of that object.
 * </ul>
 * @author Chris Vest &lt;mr.chrisvest@gmail.com&gt;
 *
 */
public interface Poolable {
  /**
   * Release this Poolable object back into the pool from where it came,
   * so that others can claim it, or the pool deallocate it if it has
   * expired.
   * <p>
   * A call to this method MUST delegate to a call to
   * {@link Slot#release(Poolable) release} on the Slot object for which this
   * Poolable was allocated, giving itself as the Poolable parameter.
   */
  void release();
}
