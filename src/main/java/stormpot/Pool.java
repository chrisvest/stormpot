/*
 * Copyright © 2011-2019 Chris Vest (mr.chrisvest@gmail.com)
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package stormpot;

/**
 * A Pool is a self-renewable set of objects from which one can claim exclusive
 * access to elements, until they are released back into the pool.
 *
 * Pools are thread-safe, and their {@link #claim(Timeout)} methods can be
 * called concurrently from multiple threads.
 * This is a strictly stronger guarantee than {@link PoolTap} provides.
 *
 * Pools extend the {@link PoolTap} class, which provides the API for accessing
 * the objects contained in the pool.
 *
 * Pools contain {@link Poolable} objects. When you claim an object in a pool,
 * you also take upon yourself the responsibility of eventually
 * {@link Poolable#release() releasing} that object again. By far the most
 * common idiom to achieve this is with a `try-finally` clause:
 *
 * [source,java]
 * ----
 * include::../src/test/java/examples/Examples.java[tag=poolClaimExample]
 * ----
 *
 * The pools are resizable, and can have their capacity changed at any time
 * after they have been created.
 *
 * All pools are configured with a certain size, the number of objects that the
 * pool will have allocated at any one time, and they can change this number on
 * the fly using the {@link #setTargetSize(int)} method. The change does not
 * take effect immediately, but instead moves the "goal post" and leaves the
 * pool to move towards it at its own pace.
 *
 * No guarantees can be made about when the pool will actually reach the target
 * size, because it might depend on how long it takes for a certain number of
 * objects to be released back into the pool.
 *
 * Pools are themselves lifecycled, and should be {@link #shutdown() shut down}
 * when they are no longer needed.
 *
 * Note that Pools are not guaranteed to have overwritten the
 * {@code Object#finalize()} method. Pools are expected to rely on explicit
 * clean-up for releasing their resources.
 *
 * @author Chris Vest <mr.chrisvest@gmail.com>
 * @param <T> the type of {@link Poolable} contained in the pool, as determined
 * by the {@linkplain Pool#from(Allocator) configured allocator}.
 * @see stormpot.PoolTap
 */
public abstract class Pool<T extends Poolable> extends PoolTap<T> {

  /**
   * Get a {@link PoolBuilder} based on the given {@link Allocator} or
   * {@link Reallocator}, which can then in turn be used to
   * {@linkplain PoolBuilder#build build} a {@link Pool} instance with the
   * desired configuration.
   *
   * @param allocator The allocator we want our pools to use. This cannot be
   * `null`.
   * @param <T> The type of {@link Poolable} that is created by the allocator,
   * and the type of objects that the configured pools will contain.
   * @return A {@link PoolBuilder} that admits additional configurations,
   * before the pool instance is {@linkplain PoolBuilder#build() built}.
   */
  public static <T extends Poolable> PoolBuilder<T> from(Allocator<T> allocator) {
    return new PoolBuilder<>(allocator);
  }

  /**
   * Build a {@link Pool} instance that pools the given set of objects.
   *
   * The objects in the pool are never expired, and never deallocated.
   * Explicitly expired objects simply return to the pool.
   *
   * This means that the returned pool has no background allocation thread,
   * and has no expiration checking overhead when claiming objects.
   *
   * The given objects are wrapped in {@link Pooled} objects, which
   * implement the {@link Poolable} interface.
   *
   * Shutting down the returned pool will not cause the given objects
   * to be deallocated.
   * The shut down process will complete as soon as the last claimed
   * object is released back to the pool.
   *
   * @param objects The objects the pool should contain.
   * @param <T> The type of objects being pooled.
   * @return A pool of the given objects.
   */
  @SafeVarargs
  public static <T> Pool<Pooled<T>> of(T... objects) {
    Allocator<Pooled<T>> allocator = new Allocator<>() {
      private int index;
      @Override
      public Pooled<T> allocate(Slot slot) {
        return new Pooled<>(slot, objects[index++]);
      }

      @Override
      public void deallocate(Pooled<T> poolable) {
      }
    };
    PoolBuilder<Pooled<T>> builder = new PoolBuilder<>(allocator);
    builder.setSize(objects.length);
    builder.setBackgroundExpirationEnabled(false);
    builder.setExpiration(Expiration.never());
    return new BlazePool<>(builder, AllocatorProcessFactory.DIRECT);
  }

  /**
   * Initiate the shut down process on this pool, and return a
   * {@link Completion} instance representing the shut down procedure.
   *
   * The shut down process is asynchronous, and the shutdown method is
   * guaranteed to not wait for any claimed {@link Poolable Poolables} to
   * be released.
   *
   * The shut down process cannot complete before all Poolables are released
   * back into the pool and {@link Allocator#deallocate(Poolable) deallocated},
   * and all internal resources (such as threads, for instance) have been
   * released as well. Only when all of these things have been taken care of,
   * does the await methods of the Completion return.
   *
   * Once the shut down process has been initiated, that is, as soon as this
   * method is called, the pool can no longer be used and all calls to
   * {@link #claim(Timeout)} will throw an {@link IllegalStateException}.
   * Threads that are already waiting for objects in the claim method, will
   * also wake up and receive an {@link IllegalStateException}.
   *
   * All objects that are already claimed when this method is called,
   * will continue to function until they are
   * {@link Poolable#release() released}.
   *
   * The shut down process is guaranteed to never deallocate objects that are
   * currently claimed. Their deallocation will wait until they are released.
   *
   * @return A {@link Completion} instance that represents the shut down
   * process.
   */
  public abstract Completion shutdown();

  /**
   * Set the target size for this pool. The pool will strive to keep this many
   * objects allocated at any one time.
   *
   * If the new target size is greater than the old one, the pool will allocate
   * more objects until it reaches the target size. If, on the other hand, the
   * new target size is less than the old one, the pool will deallocate more
   * and allocate less, until the new target size is reached.
   *
   * No guarantees are made about when the pool actually reaches the target
   * size. In fact, it may never happen as the target size can be changed as
   * often as one sees fit.
   *
   * Pools that do not support a size less than 1 (which would deviate from the
   * standard configuration space) will throw an
   * {@link IllegalArgumentException} if passed 0 or less.
   *
   * Pools that do not support online resizing will throw an
   * {@link UnsupportedOperationException}.
   *
   * @param size The new target size of the pool
   */
  public abstract void setTargetSize(int size);

  /**
   * Get the currently configured target size of the pool. Note that this is
   * _not_ the number of objects currently allocated by the pool - only
   * the number of allocations the pool strives to keep alive.
   *
   * @return The current target size of this pool.
   */
  public abstract int getTargetSize();

  /**
   * Get the {@link ManagedPool} instance that represents this pool.
   * @return The {@link ManagedPool} instance for this pool.
   */
  public abstract ManagedPool getManagedPool();

  /**
   * Get a thread-safe {@link PoolTap} implementation for this pool, which can
   * be freely shared among multiple threads.
   * @return A thread-safe {@link PoolTap}.
   */
  public final PoolTap<T> getThreadSafeTap() {
    // We use the anonymous inner class to enforce encapsulation, which is
    // probably the only reason anyone would wan to use this.
    return new PoolTap<>() {
      @Override
      public T claim(Timeout timeout) throws PoolException, InterruptedException {
        return Pool.this.claim(timeout);
      }
    };
  }

  /**
   * Get a {@link PoolTap} that only support access by one thread at a time.
   * In other words, where {@link #claim(Timeout)} cannot be called
   * concurrently in multiple threads *on the same tap*.
   *
   * The pool itself will still be thread-safe, but each thread that wishes to
   * access the pool via a thread-local tap, must have their own tap instance.
   *
   * It is a use error to access these pool taps concurrently from multiple
   * threads, or to transfer them from one thread to another without safe
   * publication.
   *
   * @return A thread-locao {@link PoolTap}.
   */
  public abstract PoolTap<T> getThreadLocalTap();
}
