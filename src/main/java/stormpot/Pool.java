package stormpot;

/**
 * A Pool is a self-renewable set of objects from which one can claim exclusive
 * access to elements, until they are released back into the pool.
 * <p>
 * Pools contain {@link Poolable} objects. When you claim an object in a pool,
 * you also take upon yourself the responsibility of eventually
 * {@link Poolable#release() releasing} that object again. The by far most
 * common idiom to achieve this is with a try-finally clause:
 * <pre><code> SomePoolable obj = pool.claim();
 * try {
 *   // do useful things with 'obj'
 * } finally {
 *   obj.release();
 * }</code></pre>
 * <h3>Promises</h3>
 * A pool makes a certain number of promises about its implementation and
 * behaviour:
 * <ul>
 * <li>A pool will contain at least one object, provided it is able to allocate
 * one. In other words, a pool cannot be constructed with a size less than 1.
 * <li>A pool will ensure that it at no point in time has more allocated
 * objects than what its size permits. As an effect of this, a pool of size 1
 * that contains an expired object, will deallocate this one object before
 * allocating its replacement.
 * <li>A pool will use the {@link Allocator} provided in its
 * {@link Config configuration} for allocating and deallocating objects.
 * <li>A call to {@link #claim()} on a depleted pool will wait until an object
 * is released and claimed by the current thread.
 * <li>A pool will reuse allocated objects within a period specified by the
 * configured {@link Config#setTTL(long, java.util.concurrent.TimeUnit)
 * time-to-live}.
 * <li>A pool will replace expired objects - objects that have, or are about
 * to, outlive the time-to-live - with new ones, to the best of its effort.
 * <li>A pool will not deallocate an object more than once. Not even if it is
 * expired, and released more than once by mistake.
 * <li>A pool will let exceptions thrown by the Allocators
 * {@link Allocator#allocate(Slot) allocate} method
 * propagate through claim, wrapped in a {@link PoolException}.
 * <li>A pool will maintain its invariants, and remain usable, after the
 * Allocators' allocate method has thrown an exception. And should the
 * Allocator stop throwing exceptions from allocate(), then the pool will
 * return to functioning normally.
 * <li>A pool will silently swallow exceptions thrown by the Allocators
 * {@link Allocator#deallocate(Poolable) deallocate} method. Those who are
 * interested in the exceptions thrown by this method, must wrap their
 * Allocators in error-checking code.
 * </ul>
 * Pools that are also {@link LifecycledPool lifecycled} make an additional
 * number of promises:
 * <ul>
 * <li>A pool that has been shut down will prevent claims by throwing an
 * {@link IllegalStateException}.
 * <li>Threads that are waiting in claim() for an object to become available,
 * will also receive and {@link IllegalStateException} when the pool is shut
 * down.
 * <li>A pool will deallocate all of its objects, before the shut down
 * procedure completes.
 * <li>The shut down procedure of a pool will not deallocate anything other
 * than objects that has been allocated by the Allocator - no nulls or other
 * types of garbage.
 * <li>Calling {@link LifecycledPool#shutdown() shutdown} on a LifecycledPool
 * will return fast, even if objects are claimed at the time of invocation.
 * <li>{@link Completion#await() Awaiting} the completion of the shut down
 * procedure of a pool, will return when all claimed objects are released and
 * subsequently deallocated, and all internal resources of the pool have been
 * freed.
 * <li>{@link Completion#await(long, java.util.concurrent.TimeUnit)
 * Awaiting the completion with a timeout} will return <code>false</code> if
 * the wait time elapses before the shut down procedure completes. If it does
 * complete, however, then the method returns <code>true</code>. Awaiting the
 * completion with a timeout, of a shut down procedure that has already
 * finished by the time the await method is called, will immediately return
 * <code>true</code>.
 * <li>A pool will silently swallow exceptions thrown the Allocators
 * deallocate method that are thrown during the shut down procedure.
 * </ul>
 * @author Chris Vest &lt;mr.chrisvest@gmail.com&gt;
 *
 * @param <T> the type of {@link Poolable} contained in the pool, as determined
 * by the {@link Config#setAllocator(Allocator) configured allocator}.
 */
public interface Pool<T extends Poolable> {
  /**
   * Claim the exclusive rights until released, to an object in the pool,
   * possibly waiting for one to become available if the pool has been
   * depleted.
   * @return
   */
  T claim() throws PoolException;
}
