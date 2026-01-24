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

import stormpot.internal.PoolBuilderImpl;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;

/**
 * Reallocators are a special kind of {@link Allocator} that can reallocate
 * {@link Poolable}s that have expired. This is useful since the {@link Pool}
 * will typically keep the Poolables around for a relatively long time, with
 * respect to garbage collection. Because of this, there is a high chance that
 * the Poolables tenure to the old generation during their life-time. This
 * way, pools often cause a slow, but steady accretion of old generation
 * garbage, ultimately helping to increase the frequency of expensive full
 * collections.
 * <p>
 * The accretion of old generation garbage is inevitable, but the rate can be
 * slowed by reusing as much as possible of the Poolable instances, when they
 * are to be reallocated. This interface is only here to enable this
 * optimization, and implementing it is completely optional.
 *
 * @author Chris Vest
 *
 * @param <T> any type that implements Poolable.
 * @since 2.2
 */
public interface Reallocator<T extends Poolable> extends Allocator<T> {
  /**
   * Possibly reallocate the given instance of T for the given slot, and
   * return it if the reallocation was successful, or a fresh replacement if
   * the instance could not be reallocated.
   * <p>
   * This method is effectively equivalent to the following:
   * {@snippet class=Examples region=reallocatorExample}
   *
   * With the only difference that it may, if possible, reuse the given
   * expired Poolable, either wholly or in part.
   * <p>
   * The state stored in the {@link stormpot.SlotInfo} for the object is reset
   * upon reallocation, just like it would be in the case of a normal
   * deallocation-allocation cycle.
   * <p>
   * Exceptions thrown by this method may propagate out through the
   * {@link Pool#claim(Timeout) claim} method of a pool, in the form of being
   * wrapped inside a {@link PoolException}. Pools must be able to handle these
   * exceptions in a sane manner, and are guaranteed to return to a working
   * state if a Reallocator stops throwing exceptions from its reallocate
   * method.
   * <p>
   * Be aware that if the reallocation of an object fails with an exception,
   * then no attempts will be made to explicitly deallocate that object. This
   * way, a failed reallocation is implicitly understood to effectively be a
   * successful deallocation.
   *
   * @param slot The slot the pool wish to allocate an object for.
   * Implementers do not need to concern themselves with the details of a
   * pools slot objects. They just have to call release on them as the
   * protocol demands.
   * @param poolable The non-null Poolable instance to be reallocated.
   * @return A fresh or rejuvenated instance of T. Never {@code null}.
   * @throws Exception If the allocation fails.
   * @see #allocate(Slot)
   * @see #deallocate(Poolable)
   */
  T reallocate(Slot slot, T poolable) throws Exception;

  /**
   * Start the task of reallocating the given instance of T for the given slot, and
   * produce the new instance if the reallocation was successful, or a fresh replacement if
   * the instance could not be reallocated.
   * <p>
   * This method works as if a new thread is started to call {@link #reallocate(Slot, Poolable)}.
   *
   * @param slot The slot the pool wish to allocate an object for.
   * Implementers do not need to concern themselves with the details of a
   * pools slot objects. They just have to call release on them as the
   * protocol demands.
   * @param poolable The non-null Poolable instance to be reallocated.
   * @return A {@link CompletionStage} that completes with a fresh,
   * or rejuvenated instance of T, or completes with an exception.
   * Never {@code null}.
   * @see #reallocate(Slot, Poolable)
   * @see #allocate(Slot)
   * @see #deallocate(Poolable)
   * @since 4.2
   */
  default CompletionStage<T> reallocateAsync(Slot slot, T poolable) {
    CompletableFuture<T> task = new CompletableFuture<>();
    PoolBuilderImpl.THREAD_BUILDER.start(() -> {
      try {
        task.complete(reallocate(slot, poolable));
      } catch (Exception e) {
        task.completeExceptionally(e);
      }
    });
    return task;
  }
}
