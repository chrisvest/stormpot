/*
 * Copyright © 2011-2024 Chris Vest (mr.chrisvest@gmail.com)
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

import stormpot.internal.PoolBuilderImpl;

import static stormpot.internal.AllocationProcess.direct;
import static stormpot.internal.AllocationProcess.inline;
import static stormpot.internal.AllocationProcess.threaded;

/**
 * A Pool is a self-renewable set of objects from which one can claim exclusive
 * access to elements, until they are released back into the pool.
 * <p>
 * Pools are thread-safe, and their {@link #claim(Timeout)} methods can be
 * called concurrently from multiple threads.
 * This is a strictly stronger guarantee than {@link PoolTap} provides.
 * <p>
 * Pools extend the {@link PoolTap} class, which provides the API for accessing
 * the objects contained in the pool.
 * <p>
 * Pools contain {@link Poolable} objects. When you claim an object in a pool,
 * you also take upon yourself the responsibility of eventually
 * {@link Poolable#release() releasing} that object again. By far the most
 * common idiom to achieve this is with a {@code try-finally} clause:
 * {@snippet class=Examples region=poolClaimExample }
 * <p>
 * The pools are resizable, and can have their capacity changed at any time
 * after they have been created.
 * <p>
 * All pools are configured with a certain size, the number of objects that the
 * pool will have allocated at any one time, and they can change this number on
 * the fly using the {@link #setTargetSize(int)} method. The change does not
 * take effect immediately, but instead moves the "goal post" and leaves the
 * pool to move towards it at its own pace.
 * <p>
 * No guarantees can be made about when the pool will actually reach the target
 * size, because it might depend on how long it takes for a certain number of
 * objects to be released back into the pool.
 *
 * <table>
 * <caption>Note</caption>
 * <tr>
 * <th scope="row">NOTE</th>
 * <td>
 * Pools created with {@link Pool#of(Object[])} are <em>not</em> resizable, and calling
 * {@link Pool#setTargetSize(int)} or {@link ManagedPool#setTargetSize(int)} will
 * cause an {@link UnsupportedOperationException} to be thrown.
 * </td>
 * </tr>
 * </table>
 *
 * Pools are themselves life-cycled, and should be {@link #shutdown() shut down}
 * when they are no longer needed.
 * <p>
 * Note that Pools don't override the deprecated {@code Object.finalize()} method.
 * Pools are expected to rely on explicit clean-up for releasing their resources.
 *
 * @author Chris Vest
 * @param <T> the type of {@link Poolable} contained in the pool, as determined
 * by the configured allocator.
 * @see stormpot.PoolTap
 */
public interface Pool<T extends Poolable> extends PoolTap<T> {
  /**
   * Get a {@link PoolBuilder} based on the given {@link Allocator} or
   * {@link Reallocator}, which can then in turn be used to
   * {@linkplain PoolBuilder#build build} a {@link Pool} instance with the
   * desired configuration.
   * <p>
   * This method is synonymous for {@link #fromThreaded(Allocator)}.
   *
   * @param allocator The allocator we want our pools to use. This cannot be
   * {@code null}.
   * @param <T> The type of {@link Poolable} that is created by the allocator,
   * and the type of objects that the configured pools will contain.
   * @return A {@link PoolBuilder} that admits additional configurations,
   * before the pool instance is {@linkplain PoolBuilder#build() built}.
   * @see #fromThreaded(Allocator)
   */
  static <T extends Poolable> PoolBuilder<T> from(Allocator<T> allocator) {
    return fromThreaded(allocator);
  }

  /**
   * Get a {@link PoolBuilder} based on the given {@link Allocator} or
   * {@link Reallocator}, which can then in turn be used to
   * {@linkplain PoolBuilder#build build} a <em>threaded</em> {@link Pool} instance with the
   * desired configuration.
   * <p>
   * The returned {@link PoolBuilder} will build pools that allocate and deallocate
   * objects in a background thread.
   * Hence, the "threaded" in the name; the objects are created and destroyed
   * by the background thread, out of band with the threads that call into the pool to
   * claim objects.
   * <p>
   * By moving the allocation of objects to a background thread, it can be guaranteed
   * that the timeouts given to {@link #claim(Timeout) claim} will always be honoured.
   * In other words, a slow allocation cannot block a {@link #claim(Timeout) claim}
   * call beyond its intended timeout.
   *
   * @param allocator The allocator we want our pools to use. This cannot be
   * {@code null}.
   * @param <T> The type of {@link Poolable} that is created by the allocator,
   * and the type of objects that the configured pools will contain.
   * @return A {@link PoolBuilder} that admits additional configurations,
   * before the pool instance is {@linkplain PoolBuilder#build() built}.
   */
  static <T extends Poolable> PoolBuilder<T> fromThreaded(Allocator<T> allocator) {
    return new PoolBuilderImpl<>(threaded(), allocator);
  }

  /**
   * Get a {@link PoolBuilder} based on the given {@link Allocator} or
   * {@link Reallocator}, which can then in turn be used to
   * {@linkplain PoolBuilder#build() build} a {@link Pool} operating in the
   * <em>inline</em> mode, with the desired configuration.
   * <p>
   * The returned {@link PoolBuilder} will build pools that allocate and deallocate
   * objects in-line with, and as part of, the {@linkplain #claim(Timeout) claim} calls.
   * Hence, the "inline" in the name.
   * <p>
   * This way, pools operating in the inline mode do not need a background thread.
   * This means that it is harder for {@linkplain #claim(Timeout) claim} to honour the
   * given {@link Timeout}.
   * It also means that the background services, such as background expiration checking,
   * and automatically replacing failed allocations, are not available.
   * On the other hand, inline pool consumes fewer memory and CPU resources.
   *
   * @param allocator The allcoator we want our pools to use. This cannot be {@code null}.
   * @param <T> The type of {@link Poolable} that is created by the allocator,
   *           and the type of objects that the configured pools will contain.
   * @return A {@link PoolBuilder} that admits additional configurations,
   * before the pool instance is {@linkplain PoolBuilder#build() built}.
   */
  static <T extends Poolable> PoolBuilder<T> fromInline(Allocator<T> allocator) {
    return new PoolBuilderImpl<>(inline(), allocator);
  }

  /**
   * Build a {@link Pool} instance that pools the given set of objects.
   * <p>
   * The objects in the pool are never expired, and never deallocated.
   * Explicitly expired objects simply return to the pool.
   * <p>
   * This means that the returned pool has no background allocation thread,
   * and has no expiration checking overhead when claiming objects.
   * <p>
   * The given objects are wrapped in {@link Pooled} objects, which
   * implement the {@link Poolable} interface.
   * <p>
   * Shutting down the returned pool will not cause the given objects
   * to be deallocated.
   * The shutdown process will complete as soon as the last claimed
   * object is released back to the pool.
   *
   * <table>
   * <caption>Note</caption>
   * <tr>
   * <th scope="row">NOTE</th>
   * <td>
   * Passing duplicate objects to this method is not supported.
   * The behaviour of the pool is unspecified if there are duplicates among
   * the objects passed as arguments to this method.
   * </td>
   * </tr>
   * </table>
   *
   * @param objects The objects the pool should contain.
   * @param <T> The type of objects being pooled.
   * @return A pool of the given objects.
   */
  @SafeVarargs
  static <T> Pool<Pooled<T>> of(T... objects) {
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
    PoolBuilder<Pooled<T>> builder = new PoolBuilderImpl<>(direct(), allocator);
    builder.setSize(objects.length);
    return builder.build();
  }

  /**
   * Initiate the shutdown process on this pool, and return a
   * {@link Completion} instance representing the shutdown procedure.
   * <p>
   * The shutdown process is asynchronous, and the shutdown method is
   * guaranteed to not wait for any claimed {@link Poolable Poolables} to
   * be released.
   * <p>
   * The shutdown process cannot complete before all Poolables are released
   * back into the pool and {@link Allocator#deallocate(Poolable) deallocated},
   * and all internal resources (such as threads, for instance) have been
   * released as well. Only when all of these things have been taken care of,
   * does the await methods of the Completion return.
   * <p>
   * Once the shutdown process has been initiated, that is, as soon as this
   * method is called, the pool can no longer be used and all calls to
   * {@link #claim(Timeout)} will throw an {@link IllegalStateException}.
   * Threads that are already waiting for objects in the claim method, will
   * also wake up and receive an {@link IllegalStateException}.
   * <p>
   * All objects that are already claimed when this method is called,
   * will continue to function until they are
   * {@link Poolable#release() released}.
   * <p>
   * The shutdown process is guaranteed to never deallocate objects that are
   * currently claimed. Their deallocation will wait until they are released.
   *
   * @return A {@link Completion} instance that represents the shutdown
   * process.
   */
  Completion shutdown();

  /**
   * Set the target size for this pool. The pool will strive to keep this many
   * objects allocated at any one time.
   * <p>
   * If the new target size is greater than the old one, the pool will allocate
   * more objects until it reaches the target size. If, on the other hand, the
   * new target size is less than the old one, the pool will deallocate more
   * and allocate less, until the new target size is reached.
   * <p>
   * No guarantees are made about when the pool actually reaches the target
   * size. In fact, it may never happen as the target size can be changed as
   * often as one sees fit.
   * <p>
   * Pools that do not support a size less than 0 (which would deviate from the
   * standard configuration space) will throw an
   * {@link IllegalArgumentException} if passed -1 or less.
   * <p>
   * Pools that do not support online resizing will throw an
   * {@link UnsupportedOperationException}.
   *
   * @param size The new target size of the pool
   */
  void setTargetSize(int size);

  /**
   * Get the currently configured target size of the pool. Note that this is
   * <em>not</em> the number of objects currently allocated by the pool - only
   * the number of allocations the pool strives to keep alive.
   *
   * @return The current target size of this pool.
   */
  int getTargetSize();

  /**
   * Get the {@link ManagedPool} instance that represents this pool.
   * @return The {@link ManagedPool} instance for this pool.
   */
  ManagedPool getManagedPool();

  /**
   * Get a thread-safe {@link PoolTap} implementation for this pool, which can
   * be freely shared among multiple threads.
   * <p>
   * If the pool tap will be accessed by {@linkplain Thread#isVirtual() virtual threads},
   * then {@link #getVirtualThreadSafeTap()} should be used instead.
   *
   * @return A thread-safe {@link PoolTap}.
   */
  PoolTap<T> getThreadSafeTap();

  /**
   * Get a thread-safe {@link PoolTap} implementation for this pool, which can
   * be freely shared among multiple threads, including {@linkplain Thread#isVirtual() virtual threads}.
   * <p>
   * The implementation of this {@link PoolTap} avoids the use of {@link ThreadLocal} variables,
   * locking mechanisms, and other API calls that are poorly supported by, or disruptive to,
   * virtual threads.
   *
   * @return A thread-safe {@link PoolTap}.
   */
  PoolTap<T> getVirtualThreadSafeTap();

  /**
   * Get a {@link PoolTap} that only support access by one thread at a time.
   * In other words, where {@link #claim(Timeout)} cannot be called
   * concurrently in multiple threads *on the same tap*.
   * <p>
   * The pool itself will still be thread-safe, but each thread that wishes to
   * access the pool via a thread-local tap, must have their own tap instance.
   * <p>
   * It is a use error to access these pool taps concurrently from multiple
   * threads, or to transfer them from one thread to another without safe
   * publication.
   *
   * @return A thread-local {@link PoolTap}.
   */
  PoolTap<T> getSingleThreadedTap();
}
