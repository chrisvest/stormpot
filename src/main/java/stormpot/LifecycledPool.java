package stormpot;

/**
 * A LifecycledPool is a {@link Pool} that can be shut down to release any
 * internal resources it might be holding on to. Most pools are life-cycled.
 * <p>
 * Note that LifecycledPools are not guaranteed to have overwritten the
 * {@link Object#finalize()} method. LifecycledPools are expected to rely on
 * explicit clean-up, for releasing their resources.
 * @author Chris Vest &lt;mr.chrisvest@gmail.com&gt;
 *
 * @param <T> The type of {@link Poolable} contained in this pool.
 */
public interface LifecycledPool<T extends Poolable> extends Pool<T> {

  /**
   * Initiate the shut down process on this pool, and return a
   * {@link Completion} instance representing the shut down procedure.
   * <p>
   * The shut down process is asynchronous, and the shutdown method is
   * guaranteed not to wait for any claimed {@link Poolable Poolables} to
   * be released.
   * <p>
   * The shut down process cannot complete before all Poolables are released
   * back into the pool and {@link Allocator#deallocate(Poolable) deallocated},
   * and all internal resources (such as threads, for instance) have been
   * released as well. Only when all of these things have been taken care of,
   * does the await methods of the Completion return.
   * <p>
   * Once the shut down process has been initiated, that is, as soon as this
   * method is called, the pool can no longer be used and all calls to
   * {@link #claim()} will throw an {@link IllegalStateException}. However,
   * all objects that are already claimed when this method is called, will
   * continue to function until they are {@link Poolable#release() released}.
   * <p>
   * The shut down process is guaranteed to never deallocate objects that are
   * currently claimed. Their deallocation will wait until they are released.
   * @return A {@link Completion} instance that represents the shut down
   * procedure.
   */
  Completion shutdown();
}
