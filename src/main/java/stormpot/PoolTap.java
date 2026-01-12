/*
 * Copyright © Chris Vest (mr.chrisvest@gmail.com)
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

import java.util.Objects;
import java.util.Optional;
import java.util.function.Consumer;
import java.util.function.Function;

/**
 * A PoolTap provides the API for accessing objects in a {@link Pool}.
 * <p>
 * PoolTaps are not necessarily thread-safe, but pools, which extend PoolTap,
 * are always thread-safe.
 *
 * @author Chris Vest
 * @param <T> the type of {@link Poolable} contained in the pool, and made
 *          available via this pool tap, as determined by the
 *          {@linkplain Pool#from(Allocator) configured allocator}.
 * @see stormpot.Pool
 */
public interface PoolTap<T extends Poolable> {
  /**
   * Claim the exclusive rights until released, to an object in the pool.
   * Possibly waiting up to the specified amount of time, as given by the
   * provided {@link Timeout} instance, for one to become available if the
   * pool has been depleted. If the timeout elapses before an object can be
   * claimed, then {@code null} is returned instead. The timeout will be
   * honoured even if the Allocators {@link Allocator#allocate(Slot) allocate}
   * methods blocks forever. If the given timeout has a zero or negative value,
   * then the method will not wait.
   * <p>
   * If the current thread has already one or more objects currently claimed,
   * then a distinct object will be returned, if one is or becomes available.
   * This means that it is possible for a single thread to deplete the pool, if
   * it so desires. However, doing so is inherently deadlock prone, so avoid
   * claiming more than one object at a time per thread, if at all possible.
   * <p>
   * This method may throw a PoolException if the pool have trouble allocating
   * objects. That is, if its assigned Allocator throws exceptions from its
   * allocate method, or returns {@code null}.
   * <p>
   * An {@link InterruptedException} will be thrown if the thread has its
   * interrupted flag set upon entry to this method, or is interrupted while
   * waiting. The interrupted flag on the thread will be cleared after
   * this, as per the general contract of interruptible methods.
   * <p>
   * If the pool has been shut down, then an {@link IllegalStateException} will
   * be thrown when this method is called. Likewise, if we are waiting for an
   * object to become available, and someone shuts the pool down.
   * <p>
   * Here's an example code snippet, where an object is claimed, printed to
   * {@code System.out}, and then released back to the pool:
   * {@snippet class=Examples region=poolClaimPrintExample}
   *
   * <h4>Memory effects:</h4>
   * <ul>
   * <li>The {@link Poolable#release() release} of an object happens-before
   *   any subsequent claim or {@link Allocator#deallocate(Poolable)
   *   deallocation} of that object, and,</li>
   * <li>The {@link Allocator#allocate(Slot) allocation} of an object
   *   happens-before any claim of that object.</li>
   * </ul>
   *
   * @param timeout The timeout of the maximum permitted time-slice to wait for
   * an object to become available. A timeout with a value of zero or less,
   * means that the call will do no waiting, preferring instead to return early
   * if no objects are available.
   * @return An object of the Poolable subtype T to which the exclusive rights
   * have been claimed, or {@code null} if the timeout period elapsed
   * before an object became available.
   * @throws PoolException If an object allocation failed because the Allocator
   * threw an exception from its allocate method, or returned
   * {@code null}, or the
   * {@link Expiration#hasExpired(SlotInfo) expiration check} threw an
   * exception.
   * @throws InterruptedException if the current thread is
   * {@link Thread#interrupt() interrupted} upon entry, or becomes interrupted
   * while waiting.
   * @throws IllegalArgumentException if the {@code timeout} argument is {@code null}.
   */
  T claim(Timeout timeout) throws PoolException, InterruptedException;

  /**
   * Returns an object from the pool if the pool contains at least one valid object,
   * otherwise returns {@code null}.
   * This method will first try to return cached object if available.
   * If no locally cached object is found, it will go through objects in the pool and
   * return the first ready to claim object.
   * If all the objects in the pool is drained, then {@code null} will be returned.
   *
   * @return an object from the pool if the pool contains at least one valid object,
   * otherwise returns {@code null}.
   * @throws PoolException If an object allocation failed because the Allocator
   * threw an exception from its allocate method, or returned
   * {@code null}, or the
   * {@link Expiration#hasExpired(SlotInfo) expiration check} threw an exception.
   */
  default T tryClaim() throws PoolException {
    try {
      return claim(Timeout.ZERO_TIMEOUT);
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      return null;
    }
  }

  /**
   * Claim an object from the pool and apply the given function to it, returning
   * the result and releasing the object back to the pool.
   * <p>
   * If an object cannot be claimed within the given timeout, then
   * {@link Optional#empty()} is returned instead. The {@code empty()} value is also
   * returned if the function returns {@code null}.
   *
   * @param timeout The timeout of the maximum permitted amount of time to wait
   * for an object to become available. A timeout with a value of zero or less,
   * means that the call will do no waiting, preferring instead to return early
   * if no objects are available.
   * @param function The function to apply to the claimed object, if any. The
   * function should avoid further claims, since having more than one object
   * claimed at a time per thread is inherently deadlock prone.
   * @param <R> The return type of the given function.
   * @return an {@link Optional} of either the return value of applying the
   * given function to a claimed object, or empty if the timeout elapsed or
   * the function returned {@code null}.
   * @throws InterruptedException if the thread was interrupted.
   * @see #claim(Timeout) The {@code claim} method for more details on failure modes
   * and memory effects.
   */
  default <R> Optional<R> apply(Timeout timeout, Function<T, R> function) throws InterruptedException {
    Objects.requireNonNull(function, "Function cannot be null.");
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
   * <p>
   * If an object cannot be claimed within the given timeout, then this method
   * returns {@code false}. Otherwise, if an object was claimed and supplied to the
   * consumer, the method returns {@code true}.
   *
   * @param timeout The timeout of the maximum permitted amount of time to wait
   * for an object to become available. A timeout with a value of zero or less,
   * means that the call will do no waiting, preferring instead to return early
   * if no objects are available.
   * @param consumer The consumer to pass the claimed object to, if any. The
   * consumer should avoid further claims, since having more than one object
   * claimed at a time per thread is inherently deadlock prone.
   * @return {@code true} if an object could be claimed within the given timeout and
   * passed to the given consumer, or {@code false} otherwise.
   * @throws InterruptedException if the thread was interrupted.
   * @see #claim(Timeout) The {@code claim} method for more details on failure modes
   * and memory effects.
   */
  default boolean supply(Timeout timeout, Consumer<T> consumer)
      throws InterruptedException {
    Objects.requireNonNull(consumer, "Consumer cannot be null.");
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
