/*
 * Copyright (C) 2011-2014 Chris Vest (mr.chrisvest@gmail.com)
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *         http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package stormpot;

import java.util.Optional;
import java.util.function.Consumer;
import java.util.function.Function;

/**
 * A Pool is a self-renewable set of objects from which one can claim exclusive
 * access to elements, until they are released back into the pool.
 *
 * Pools contain {@link Poolable} objects. When you claim an object in a pool,
 * you also take upon yourself the responsibility of eventually
 * {@link Poolable#release() releasing} that object again. By far the most
 * common idiom to achieve this is with a `try-finally` clause:
 *
 * [source,java]
 * --
 * Timeout timeout = new Timeout(1, TimeUnit.SECONDS);
 * SomePoolable obj = pool.claim(timeout);
 * try {
 *   // Do useful things with 'obj'.
 *   // Note that 'obj' will be 'null' if 'claim' timed out.
 * } finally {
 *   if (obj != null) {
 *     obj.release();
 *   }
 * }
 * --
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
 * {@link Object#finalize()} method. Pools are expected to rely on explicit
 * clean-up for releasing their resources.
 *
 * @author Chris Vest <mr.chrisvest@gmail.com>
 * @param <T> the type of {@link Poolable} contained in the pool, as determined
 * by the {@link Config#setAllocator(Allocator) configured allocator}.
 */
public abstract class Pool<T extends Poolable> {
  /**
   * Claim the exclusive rights until released, to an object in the pool.
   * Possibly waiting up to the specified amount of time, as given by the
   * provided {@link Timeout} instance, for one to become available if the
   * pool has been depleted. If the timeout elapses before an object can be
   * claimed, then `null` is returned instead. The timeout will be
   * honoured even if the Allocators {@link Allocator#allocate(Slot) allocate}
   * methods blocks forever. If the given timeout has a zero or negative value,
   * then the method will not wait.
   *
   * If the current thread has already one or more objects currently claimed,
   * then a distinct object will be returned, if one is or becomes available.
   * This means that it is possible for a single thread to deplete the pool, if
   * it so desires. However, doing so is inherently deadlock prone, so avoid
   * claiming more than one object at a time per thread, if at all possible.
   *
   * This method may throw a PoolException if the pool have trouble allocating
   * objects. That is, if its assigned Allocator throws exceptions from its
   * allocate method, or returns `null`.
   *
   * An {@link InterruptedException} will be thrown if the thread has its
   * interrupted flag set upon entry to this method, or is interrupted while
   * waiting. The interrupted flag on the thread will be cleared after
   * this, as per the general contract of interruptible methods.
   *
   * If the pool has been shut down, then an {@link IllegalStateException} will
   * be thrown when this method is called. Likewise if we are waiting for an
   * object to become available, and someone shuts the pool down.
   *
   * Here's an example code snippet, where an object is claimed, printed to
   * `System.out`, and then released back to the pool:
   *
   * [source,java]
   * --
   * Poolable obj = pool.claim(TIMEOUT);
   * if (obj != null) {
   *   try {
   *     System.out.println(obj);
   *   } finally {
   *     obj.release();
   *   }
   * }
   * --
   *
   * Memory effects:
   *
   * * The {@link Poolable#release() release} of an object happens-before
   *   any subsequent claim or {@link Allocator#deallocate(Poolable)
   *   deallocation} of that object, and,
   * * The {@link Allocator#allocate(Slot) allocation} of an object
   *   happens-before any claim of that object.
   *
   * @param timeout The timeout of the maximum permitted time-slice to wait for
   * an object to become available. A timeout with a value of zero or less
   * means that the call will do no waiting, preferring instead to return early
   * if no objects are available.
   * @return An object of the Poolable subtype T to which the exclusive rights
   * have been claimed, or `null` if the timeout period elapsed
   * before an object became available.
   * @throws PoolException If an object allocation failed because the Allocator
   * threw an exception from its allocate method, or returned
   * `null`, or the
   * {@link Expiration#hasExpired(SlotInfo) expiration check} threw an
   * exception.
   * @throws InterruptedException if the current thread is
   * {@link Thread#interrupt() interrupted} upon entry, or becomes interrupted
   * while waiting.
   * @throws IllegalArgumentException if the `timeout` argument is `null`.
   */
  public abstract T claim(Timeout timeout)
      throws PoolException, InterruptedException;

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
   * @param size The new target size of the pool
   */
  public abstract void setTargetSize(int size);

  /**
   * Get the currently configured target size of the pool. Note that this is
   * _not_ the number of objects currently allocated by the pool - only
   * the number of allocations the pool strives to keep alive.
   * @return The current target size of this pool.
   */
  public abstract int getTargetSize();

  /**
   * Claim an object from the pool and apply the given function to it, returning
   * the result and releasing the object back to the pool.
   *
   * If an object cannot be claimed within the given timeout, then
   * {@link Optional#empty()} is returned instead. The `empty()` value is also
   * returned if the function returns `null`.
   *
   * @param timeout The timeout of the maximum permitted amount of time to wait
   * for an object to become available. A timeout with a value of zero or less
   * means that the call will do no waiting, preferring instead to return early
   * if no objects are available.
   * @param function The function to apply to the claimed object, if any. The
   * function should avoid further claims, since having more than one object
   * claimed at a time per thread is inherently deadlock prone.
   * @param <R> The return type of the given function.
   * @return an {@link Optional} of either the return value of applying the
   * given function to a claimed object, or empty if the timeout elapsed or
   * the function returned `null`.
   * @throws InterruptedException if the thread was interrupted.
   * @see #claim(Timeout) for more details on failure modes and memory effects.
   */
  public final <R> Optional<R> apply(Timeout timeout, Function<T, R> function)
      throws InterruptedException {
    T obj = claim(timeout);
    if (obj == null) {
      return Optional.empty();
    }
    try {
      return Optional.ofNullable(function.apply(obj));
    } finally {
      obj.release();
    }
  }

  /**
   * Claim an object from the pool and supply it to the given consumer, and then
   * release it back to the pool.
   *
   * If an object cannot be claimed within the given timeout, then this method
   * returns `false`. Otherwise, if an object was claimed and supplied to the
   * consumer, the method returns `true`.
   * @param timeout The timeout of the maximum permitted amount of time to wait
   * for an object to become available. A timeout with a value of zero or less
   * means that the call will do no waiting, preferring instead to return early
   * if no objects are available.
   * @param consumer The consumer to pass the claimed object to, if any. The
   * consumer should avoid further claims, since having more than one object
   * claimed at a time per thread is inherently deadlock prone.
   * @return `true` if an object could be claimed within the given timeout and
   * passed to the given consumer, or `false` otherwise.
   * @throws InterruptedException if the thread was interrupted.
   * @see #claim(Timeout) for more details on failure modes and memory effects.
   */
  public final boolean supply(Timeout timeout, Consumer<T> consumer)
      throws InterruptedException {
    T obj = claim(timeout);
    if (obj == null) {
      return false;
    }
    try {
      consumer.accept(obj);
      return true;
    } finally {
      obj.release();
    }
  }
}
