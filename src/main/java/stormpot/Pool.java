/*
 * Copyright 2011 Chris Vest
 * 
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 * 
 *      http://www.apache.org/licenses/LICENSE-2.0
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
 * <p>
 * Pools contain {@link Poolable} objects. When you claim an object in a pool,
 * you also take upon yourself the responsibility of eventually
 * {@link Poolable#release() releasing} that object again. By far the most
 * common idiom to achieve this is with a try-finally clause:
 * <pre><code> Timeout timeout = new Timeout(1, TimeUnit.SECONDS);
 * SomePoolable obj = pool.claim(timeout);
 * try {
 *   // do useful things with 'obj'
 * } finally {
 *   if (obj != null) {
 *     obj.release();
 *   }
 * }</code></pre>
 * <p>
 * See also <a href="package-summary.html#memory-effects-and-threading">
 * Memory Effects and Threading</a> for details of the behaviour in concurrent
 * programs
 * @author Chris Vest &lt;mr.chrisvest@gmail.com&gt;
 * @param <T> the type of {@link Poolable} contained in the pool, as determined
 * by the {@link Config#setAllocator(Allocator) configured allocator}.
 */
public interface Pool<T extends Poolable> {
  /**
   * Claim the exclusive rights until released, to an object in the pool.
   * Possibly waiting up to the specified amount of time, as given by the
   * provided {@link Timeout} instance, for one to become available if the
   * pool has been depleted. If the timeout elapses before an object can be
   * claimed, then <code>null</code> is returned instead. The timeout will be
   * honoured even if the Allocators {@link Allocator#allocate(Slot) allocate}
   * methods blocks forever, for some reason. If the given timeout has a zero
   * or negative value, then the method will not wait.
   * <p>
   * If the current thread has already one or more objects currently claimed,
   * then a distinct object will be returned, if one is or becomes available.
   * This means that it is possible for a single thread to deplete the pool, if
   * it so desires.
   * <p>
   * This method may throw a PoolException if the pool have trouble allocating
   * objects. That is, if its assigned Allocator throws exceptions from its
   * allocate method, or returns <code>null</code>.
   * <p>
   * An {@link InterruptedException} will be thrown if the thread has its
   * interrupted flag set upon entry to this method, or is interrupted while
   * waiting. The interrupted flag on the thread will be cleared after
   * this, as per the general contract of interruptible methods.
   * <p>
   * If the pool is a {@link LifecycledPool} and has been shut down, then an
   * {@link IllegalStateException} will be thrown when this method is called.
   * Likewise if we are waiting for an object to become available, and someone
   * shuts the pool down.
   * <p>
   * Memory effects:
   * <ul>
   * <li>The {@link Poolable#release() release} of an object happens-before
   * any subsequent claim or {@link Allocator#deallocate(Poolable)
   * deallocation} of that object, and,
   * <li>The {@link Allocator#allocate(Slot) allocation} of an object
   * happens-before any claim of that object.
   * </ul>
   * @param timeout The timeout of the maximum permitted time-slice to wait for
   * an object to become available. A timeout with a value of zero or less
   * means that the call will do no waiting, preferring instead to return early
   * if no objects are available.
   * @return An object of the Poolable subtype T to which the exclusive rights
   * have been claimed, or <code>null</code> if the timeout period elapsed
   * before an object became available.
   * @throws PoolException If an object allocation failed because the Allocator
   * threw an exception from its allocate method, or returned
   * <code>null</code>.
   * @throws InterruptedException if the current thread is
   * {@link Thread#interrupt() interrupted} upon entry, or becomes interrupted
   * while waiting.
   * @throws IllegalArgumentException if the <code>timeout</code> argument is
   * <code>null</code>.
   */
  T claim(Timeout timeout) throws PoolException, InterruptedException;
}
