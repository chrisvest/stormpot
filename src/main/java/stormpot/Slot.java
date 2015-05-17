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
 * A Slot represents a location in a Pool where objects can be allocated into,
 * so that they can be claimed and released. The Slot is used by the allocated
 * Poolables as a call-back, to tell the pool when an object is released.
 *
 * The individual pool implementations provide their own Slot implementations.
 *
 * Slots contain mutable state within them. If objects are claimed, released
 * and otherwise worked on from different threads, then the hand-over must
 * happen through a safe publication mechanism.
 *
 * See Java Concurrency in Practice by Brian Goetz for more information on safe
 * publication.
 *
 * Slots also expects single-threaded access, so they cannot be used
 * concurrently by multiple threads. Only one thread at a time can use a Slot
 * instance.
 * 
 * @author Chris Vest <mr.chrisvest@gmail.com>
 * @see Poolable
 */
public interface Slot {
  /**
   * Signal to the pool that the currently claimed object in this slot has been
   * released.
   *
   * It is a user error to release a slot that is not currently claimed. It is
   * likewise a user error to release a Slot while inside the Allocators
   * {@link Allocator#allocate(Slot) allocate} method, or the Expirations
   * {@link Expiration#hasExpired(SlotInfo) hasExpired} method.
   *
   * On the other hand, it is _not_ an error to release a Poolable
   * from a thread other than the one that claimed it.
   *
   * Pools are free to throw a PoolException if they detect any of these
   * wrong uses, but it is not guaranteed and the exact behaviour is not
   * specified. "Unspecified behaviour" means that dead-locks and infinite
   * loops are fair responses as well. Therefore, heed the advice and don't
   * misuse release!
   *
   * Pools must, however, guarantee that an object is never
   * {@link Allocator#deallocate(Poolable) deallocated} more than once.
   * This guarantee must hold even if release is misused.
   * @param obj A reference to the Poolable object that is allocated for this
   * slot, and is being released.
   */
  void release(Poolable obj);

  /**
   * Mark the object represented by this slot as expired. Expired objects
   * cannot be claimed again, and will be deallocated by the pool at the
   * earliest convenience. Objects are normally expired automatically using
   * the {@link Expiration} policy that the pool has been configured with. If
   * an object through its use is found to be unusable, then it can be
   * explicitly expired using this method. Objects can only be explicitly
   * expired while they are claimed, and such expired objects will not be
   * deallocated until they are released back to the pool.
   *
   * It is a user error to expire objects that are not currently claimed. It is
   * likewise a user error to expire a Slot while inside the Allocators
   * {@link Allocator#allocate(Slot) allocate} method.
   *
   * The expiration only takes effect after the object has been released, so
   * calling this method from within the Expirations
   * {@link Expiration#hasExpired(SlotInfo) hasExpired} method will not prevent
   * the object from being claimed, but will prevent it from being claimed
   * again after it's been released back to the pool. An Expiration policy that
   * expires all objects with this method, is effectively allowed objects to
   * only be claimed once.
   *
   * Pools are free to throw a PoolException if they detect any wrong uses,
   * but it is not guaranteed and the exact behaviour is not specified.
   * "Unspecified behaviour" means that dead-locks and infinite loops are fair
   * responses as well. Therefore, heed the advice and don't misuse expire!
   *
   * Pools must, however, guarantee that an object is never
   * {@link Allocator#deallocate(Poolable) deallocated} more than once.
   * This guarantee must hold even if expire is misused.
   * @param obj A reference to the Poolable object that is allocated for this
   * slot, and is being expired.
   */
  void expire(Poolable obj);
}
