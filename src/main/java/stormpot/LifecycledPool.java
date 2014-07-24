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

/**
 * A LifecycledPool is a {@link Pool} that can be shut down to release any
 * internal resources it might be holding on to.
 *
 * Note that LifecycledPools are not guaranteed to have overwritten the
 * {@link Object#finalize()} method. LifecycledPools are expected to rely on
 * explicit clean-up for releasing their resources.
 * @author Chris Vest <mr.chrisvest@gmail.com>
 * @see Pool
 * @param <T> The type of {@link Poolable} contained in this pool.
 */
public interface LifecycledPool<T extends Poolable> extends Pool<T> {
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
  Completion shutdown();
}
