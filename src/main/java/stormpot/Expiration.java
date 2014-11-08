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
 * The expiration is used to determine if a given slot has expired, or
 * otherwise become invalid.
 *
 * Note that Expiration instances must be thread-safe, as they may be
 * accessed concurrently by multiple threads. However, for a given
 * {@link SlotInfo} and {@link Poolable} instance, only a single thread will
 * invoke the expiration at a time. This means that there is no need to
 * synchronise on the SlotInfo or Poolable objects.
 *
 * The easiest way to ensure that an Expiration implementation is thread-safe,
 * is to make sure that they never mutate any state. If they do, however,
 * then they must do so in a thread-safe manner, unless the mutable state is
 * contained within the SlotInfo or Poolable objects – in this case, the
 * mutable state will be thread-local. Be aware that making the
 * {@link #hasExpired(SlotInfo) hasExpired} method {@code synchronized} will
 * most likely severely reduce the performance and scalability of the pool.
 *
 * The Expiration can be invoked several times during a
 * {@link Pool#claim(Timeout) claim} call, so it is important that the
 * Expiration is fast. It can easily be the dominating factor in the
 * performance of the pool.
 *
 * TIP: If the expiration is too slow, you can alternatively use a time-based
 * expiration, such as {@link stormpot.TimeExpiration}, that has been
 * configured with a very low expiration time, like a few seconds. Then you can
 * configure the pool to use a {@link stormpot.Reallocator}, where you do the
 * expensive expiration check in the
 * {@link stormpot.Reallocator#reallocate(Slot, Poolable) reallocate} method,
 * returning the same Poolable back if it hasn't expired.
 * Be aware, though, that such a scheme has to be tuned to the load of the
 * application, such that the objects in the pool don't all expire at the same
 * time, leaving the pool empty.
 *
 * @author Chris Vest <mr.chrisvest@gmail.com>
 */
public interface Expiration<T extends Poolable> {
  /**
   * Test whether the Slot and Poolable object, represented by the given
   * {@link SlotInfo} object, is still valid, or if the pool should
   * deallocate it and allocate a replacement.
   *
   * If the method throws an exception, then that is taken to mean that the
   * slot is invalid. The exception will bubble out of the
   * {@link Pool#claim(Timeout) claim} method, but the mechanism is
   * implementation specific. For this reason, it is generally advised that
   * Expirations do not throw exceptions.
   *
   * Note that this method can be called as often as several times per
   * {@link Pool#claim(Timeout) claim}. The performance of this method therefor
   * has a big influence on the perceived performance of the pool.
   *
   * @param info An informative representative of the slot being tested.
   * Never `null`.
   * @return `true` if the Slot and Poolable in question should be
   * deallocated, `false` if it is valid and eligible for claiming.
   * @throws Exception If checking the validity of the Slot or Poolable somehow
   * went wrong. In this case, the Poolable will be assumed to be expired.
   */
  boolean hasExpired(SlotInfo<? extends T> info) throws Exception;
}
