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
 * An Allocator is responsible for the creation and destruction of
 * {@link Poolable} objects.
 *
 * This is where the objects in the Pool comes from. Clients of the Stormpot
 * library needs to provide their own Allocator implementations.
 *
 * Implementations of this interface must be thread-safe, because there is no
 * knowing whether pools will try to access it concurrently or not. The easiest
 * way to achieve this is to just make the
 * {@link Allocator#allocate(Slot) allocate}
 * and {@link Allocator#deallocate(Poolable) deallocate} methods synchronised,
 * but this might cause problems for the pools ability to allocate objects fast
 * enough, if they are very slow to construct. Pools might want to deal with
 * very slow allocations by allocating in more than one thread, but
 * synchronizing the {@link #allocate(Slot)} method will render that strategy
 * ineffective.
 *
 * A better approach to thread-safety is to not have any shared mutable state
 * in the allocator, if at all possible.
 * 
 * @author Chris Vest <mr.chrisvest@gmail.com>
 * @see stormpot.Reallocator
 * @param <T> any type that implements Poolable.
 */
public interface Allocator<T extends Poolable> {

  /**
   * Create a fresh new instance of T for the given slot.
   *
   * The returned {@link Poolable} must obey the contract that, when
   * {@link Poolable#release()} is called on it, it must delegate
   * the call onto the {@link Slot#release(Poolable)} method of the here
   * given slot object.
   *
   * Exceptions thrown by this method may propagate out through the
   * {@link Pool#claim(Timeout) claim} method of a pool, in the form of being
   * wrapped inside a {@link PoolException}. Pools must be able to handle these
   * exceptions in a sane manner, and are guaranteed to return to a working
   * state if an Allocator stops throwing exceptions from its allocate method.
   * @param slot The slot the pool wish to allocate an object for.
   * Implementers do not need to concern themselves with the details of a
   * pools slot objects. They just have to call release on them as the
   * protocol demands.
   * @return A newly created instance of T. Never `null`.
   * @throws Exception If the allocation fails.
   */
  T allocate(Slot slot) throws Exception;

  /**
   * Deallocate, if applicable, the given Poolable and free any resources
   * associated with it.
   *
   * This is an opportunity to close any connections or files, flush buffers,
   * empty caches or what ever might need to be done to completely free any
   * resources represented by this Poolable.
   *
   * Note that a Poolable must never touch its slot object after it has been
   * deallocated.
   *
   * Pools, on the other hand, will guarantee that the same object is never
   * deallocated more than once.
   *
   * Note that pools will always silently swallow exceptions thrown by the
   * deallocate method. They do this because there is no knowing whether the
   * deallocation of an object will be done synchronously by a thread calling
   * {@link Poolable#release() release} on a Poolable, or asynchronously by
   * a clean-up thread inside the pool.
   *
   * Deallocation from the release of an expired object, and deallocation from
   * the shut down procedure of a {@link Pool} behave the same way in this
   * regard. They will both silently swallow any exception thrown.
   *
   * On the other hand, pools are guaranteed to otherwise correctly deal with
   * any exception that might be thrown. The shut down procedure will still
   * complete, and release will still maintain the internal data structures of
   * the pool to make the slot available for new allocations.
   *
   * If you need to somehow specially deal with the exceptions thrown by the
   * deallocation of objects, then you should do this in the allocator itself,
   * or in a wrapper around your allocator.
   * @param poolable The non-null Poolable instance to be deallocated.
   * @throws Exception if the deallocation encounters an error.
   */
  void deallocate(T poolable) throws Exception;
}
