/*
 * Copyright © 2011-2019 Chris Vest (mr.chrisvest@gmail.com)
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

import java.util.concurrent.TimeUnit;

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
 * {@link #hasExpired(SlotInfo) hasExpired} method `synchronized` will
 * most likely severely reduce the performance and scalability of the pool.
 *
 * The Expiration can be invoked several times during a
 * {@link Pool#claim(Timeout) claim} call, so it is important that the
 * Expiration is fast. It can easily be the dominating factor in the
 * performance of the pool.
 *
 * TIP: If the expiration is too slow, you can alternatively use a time-based
 * expiration, such as {@link #after(long, TimeUnit)}, that has been
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
   * Construct a new Expiration that will invalidate objects that are older
   * than the exact provided span of time in the given unit.
   *
   * If the `time` is less than one, or the `unit` is `null`, then
   * an {@link IllegalArgumentException} will be thrown.
   *
   * This expiration does not make use of the
   * {@linkplain SlotInfo#getStamp() slot info stamp}.
   *
   * @param time Poolables older than this, in the given unit, will
   *            be considered expired. This value must be at least 1.
   * @param unit The {@link TimeUnit} of the maximum permitted age.
   *            Never `null`.
   * @param <T> The type of {@link Poolable} to check.
   * @return The specified time-based expiration policy.
   */
  static <T extends Poolable> Expiration<T> after(long time, TimeUnit unit) {
    return new TimeExpiration<>(time, unit);
  }

  /**
   * Construct a new Expiration that will invalidate objects that are older
   * than the given lower bound, before they get older than the upper bound,
   * in the given time unit.
   *
   * If the `fromTime` is less than 1, the `toTime` is less than the
   * `fromTime`, or the `unit` is `null`, then an
   * {@link java.lang.IllegalArgumentException} will be thrown.
   *
   * The actual expiration time is chosen uniformly randomly within the
   * given brackets, for each allocated object.
   *
   * This expiration make use of the
   * {@linkplain SlotInfo#getStamp() slot info stamp} for storing the target
   * expiration timestamp.
   *
   * @param fromTime Poolables younger than this, in the given unit, are not
   *                 considered expired. This value must be at least 1.
   * @param toTime Poolables older than this, in the given unit, are always
   *               considered expired. This value must be greater than or equal
   *               to the lowerBound.
   * @param unit The {@link TimeUnit} of the bounds values. Never `null`.
   */
  static <T extends Poolable> Expiration<T> after(
      long fromTime, long toTime, TimeUnit unit) {
    return new TimeSpreadExpiration<>(fromTime, toTime, unit);
  }

  /**
   * Construct a new Expiration that will invalidate objects if either this, or
   * the given expiration, considers an object expired.
   *
   * This is a short-circuiting combinator, such that if this expiration
   * invalidates the object, then the other expiration will not be checked.
   *
   * This makes it easy to have an expiration that expires both on time, and
   * some other criteria.
   *
   * @param other The other expiration to compose with.
   * @return A new expiration composed of this and the other expiration.
   */
  default Expiration<T> or(Expiration<T> other) {
    return new OrExpiration<>(this, other);
  }

  /**
   * Construct a new Expiration that is composed of a time-based expiration and
   * this one, such that this expiration is only consulted at most once within
   * the given time period.
   *
   * For instance, if an expiration is expensive, you can use this to only
   * check it, say, every 5 seconds.
   *
   * @param time The time between checks.
   * @param unit The unit of the time between checks.
   * @return An expiration that only delegates to this one every so often.
   */
  default Expiration<T> every(long time, TimeUnit unit) {
    return every(time, time, unit);
  }

  /**
   * Construct a new Expiration that is composed of a time-based expiration and
   * this one, such that this expiration is only consulted at most once within
   * a time period based on the given `fromTime` and `toTime` brackets.
   *
   * For instance, if an expiration is expensive, you can use this to only
   * check it, say, every 5 to 10 seconds.
   *
   * @param fromTime The minimum amount of time before a check becomes necessary.
   * @param toTime The maximum amount of time before a check becomes necessary.
   * @param unit The unit of the time between checks.
   * @return An expiration that only delegates to this one every so often.
   */
  default Expiration<T> every(long fromTime, long toTime, TimeUnit unit) {
    return new EveryExpiration<>(this, fromTime, toTime, unit);
  }

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
