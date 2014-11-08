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
 * Reallocators are a special kind of {@link Allocator} that can reallocate
 * {@link Poolable}s that have expired. This is useful since the {@link Pool}
 * will typically keep the Poolables around for a relatively long time, with
 * respect to garbage collection. Because of this, there is a high chance that
 * the Poolables tenure to the old generation during their life time. This
 * way, pools often cause a slow, but steady accretion of old generation
 * garbage, ultimately helping to increase the frequency of expensive full
 * collections.
 *
 * The accretion of old generation garbage is inevitable, but the rate can be
 * slowed by reusing as much as possible of the Poolable instances, when they
 * are to be reallocated. This interface is only here to enable this
 * optimisation, and implementing it is completely optional.
 *
 * @author Chris Vest <mr.chrisvest@gmail.com>
 *
 * @param <T> any type that implements Poolable.
 * @since 2.2
 */
public interface Reallocator<T extends Poolable> extends Allocator<T> {
  /**
   * Possibly reallocate the given instance of T for the given slot, and
   * return it if the reallocation was successful, or a fresh replacement if
   * the instance could not be reallocated.
   *
   * This method is effectively equivalent to the following:
   *
   * [source,java]
   * --
   * deallocate(poolable);
   * return allocate(slot);
   * --
   *
   * With the only difference that it may, if possible, reuse the given
   * expired Poolable, either wholly or in part.
   *
   * The state stored in the {@link stormpot.SlotInfo} for the object is reset
   * upon reallocation, just like it would be in the case of a normal
   * deallocation-allocation cycle.
   *
   * Exceptions thrown by this method may propagate out through the
   * {@link Pool#claim(Timeout) claim} method of a pool, in the form of being
   * wrapped inside a {@link PoolException}. Pools must be able to handle these
   * exceptions in a sane manner, and are guaranteed to return to a working
   * state if a Reallocator stops throwing exceptions from its reallocate
   * method.
   *
   * Be aware that if the reallocation of an object fails with an exception,
   * then no attempts will be made to explicitly deallocate that object. This
   * way, a failed reallocation is implicitly understood to effectively be a
   * successful deallocation.
   * @param slot The slot the pool wish to allocate an object for.
   * Implementers do not need to concern themselves with the details of a
   * pools slot objects. They just have to call release on them as the
   * protocol demands.
   * @param poolable The non-null Poolable instance to be reallocated.
   * @return A fresh or rejuvenated instance of T. Never `null`.
   * @throws Exception If the allocation fails.
   * @see #allocate(Slot)
   * @see #deallocate(Poolable)
   */
  T reallocate(Slot slot, T poolable) throws Exception;
}
