package stormpot;

/**
 * An Allocator is responsible for the creation and destruction of Poolable
 * objects.
 * <p>
 * This is where the objects in the Pool comes from. Clients of the Stormpot
 * library needs to provide their own Allocator implementations.
 * <p>
 * Implementations of this interface must be thread-safe, because there is no
 * knowing whether pools will try to access it concurrently or not. Generally
 * they will probably not access it concurrently, but since no guarantee can
 * be provided one has expect that concurrent access might occur. The easiest
 * way to achieve this is to just make the
 * {@link Allocator#allocate(Slot) allocate}
 * and {@link Allocator#deallocate(Poolable) deallocate} methods synchronised.
 * @author Chris Vest <mr.chrisvest@gmail.com>
 *
 * @param <T> any type that implements Poolable.
 */
public interface Allocator<T extends Poolable> {

  /**
   * Create a fresh new instance of T for the given slot.
   * <p>
   * The returned {@link Poolable} must obey the contract that, when
   * {@link Poolable#release() release} is called on it, it must delegate
   * the call onto the {@link Slot#release() release} method of the here
   * given slot object.
   * @param slot The slot the pool wish to allocate an object for.
   * Implementors do not need to concern themselves with the details of a
   * pools slot objects. They just have to call release on them as the
   * protocol demands.
   * @return A newly created instance of T.
   */
  T allocate(Slot slot);

  /**
   * Deallocate, if applicable, the given Poolable and free any resources
   * associated with it.
   * <p>
   * This is an opportunity to close any connections or files, flush buffers,
   * empty caches or what ever might need to be done to completely free any
   * resources represented by this Poolable.
   * <p>
   * Note that a Poolable must never touch its slot object after it has been
   * deallocated.
   * <p>
   * Pools, on the other hand, will guarantee that the same object is never
   * deallocated more than once.
   * @param poolable a non-null Poolable instance to be deallocated.
   */
  void deallocate(T poolable);
}