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

import java.util.concurrent.TimeUnit;

/**
 * The basic configuration "bean" class.
 * <p>
 * Instances of this class is passed to the constructors of {@link Pool pools}
 * so that they know how big they should be, how to allocate objects and for
 * how long the objects should live.
 * <p>
 * This class is made thread-safe by having the fields be protected by a lock.
 * The details of this locking mechanism should not be relied upon by client
 * code.
 * <p>
 * The various set* methods are made to return the Config instance itself, so
 * that the method calls may be chained if so desired.
 * 
 * <h3>Standardised configuration</h3>
 * The contract of the Config class, and how Pools will interpret it, is within
 * the context of a so-called standardised configuration. All pool and Config
 * implementations must behave similarly in a standardised configuration.
 * <p>
 * It is conceivable that some pool implementations will come with their own
 * sub-classes of Config, that allow greater control over the pools behaviour.
 * It is even permissible that these pool implementations may
 * deviate from the contract of the Pool interface. However, they are only
 * allowed to do so in a non-standard configuration. That is, any deviation
 * from the specified contracts must be explicitly enabled.
 * 
 * @author Chris Vest &lt;mr.chrisvest@gmail.com&gt;
 * @param <T> The type of {@link Poolable} objects that a {@link Pool} based
 * on this Config will produce.
 */
@SuppressWarnings("unchecked")
public class Config<T extends Poolable> {

  private int size = 10;
  private Expiration<? super T> expiration =
      new TimeExpiration(600, TimeUnit.SECONDS); // 10 minutes
  private Allocator<?> allocator;
  
  /**
   * Build a new empty Config object. Most settings have reasonable default
   * values. However, no {@link Allocator} is configured by default, and one
   * must make sure to set one.
   */
  public Config() {
  }

  /**
   * Set the size of the pools we want to configure them with. Pools are
   * required to control the allocations and deallocations, such that no more
   * than this number of objects are allocated at any time.
   * <p>
   * This means that a pool of size 1, whose single object have expired, will
   * deallocate that one object before allocating a replacement.
   * <p>
   * The size must be at least one for standard pool configurations. A Pool
   * will throw an {@link IllegalArgumentException} from their constructor if
   * this is not the case.
   * @param size The target pool size. Must be at least 1.
   * @return This Config instance.
   */
  public synchronized Config<T> setSize(int size) {
    this.size = size;
    return this;
  }

  /**
   * Get the currently configured size. Default is 10.
   * @return The configured pool size.
   */
  public synchronized int getSize() {
    return size;
  }

  /**
   * Set the time-to-live for the objects in the pools we want to configure.
   * Objects older than this value will be reclaimed and their slot
   * re-allocated.
   * <p>
   * Note that no guarantee is given for how much older than the TTL an object
   * may be, before it is deallocated. Further, the liveness checking is
   * inherently racy, so objects that are close to the edge of their TTL might
   * actually have expired by the time they are returned from a claim method.
   * Pools are permitted to try and account for this by pro-actively
   * re-allocating objects before their TTL expires, but this behaviour is not
   * part of the Pool contract and as such should generally not be relied upon. 
   * <p>
   * The <code>ttl</code> value must be at least 1 for standard pool
   * configurations. Further, the <code>unit</code> must be non-null.
   * A Pool will throw an {@link IllegalArgumentException} from their
   * constructor if this is not the case.
   * @param ttl The scalar value of the time-to-live value. Must be at least 1.
   * @param unit The unit of the 'ttl' value. Cannot be <code>null</code>.
   * @return This Config instance.
   */
//  public synchronized Config<T> setTTL(long ttl, TimeUnit unit) {
//    this.ttl = ttl;
//    this.ttlUnit = unit;
//    return this;
//  }

  /**
   * Set the {@link Allocator} to use for the pools we want to configure.
   * This will change the type-parameter of the Config object to match that
   * of the new Allocator.
   * <p>
   * The allocator is initially <code>null</code> in a new Config object, and
   * can be set to <code>null</code> any time. However, in a standard
   * configuration, it must be non-null when the Config is passed to a Pool
   * constructor. Otherwise the constructor will throw an
   * {@link IllegalArgumentException}.
   * @param <X> The type of {@link Poolable} that is created by the allocator,
   * and the type of objects that the configured pools will contain.
   * @param allocator The allocator we want our pools to use.
   * @return This Config instance, but with a generic type parameter that
   * matches that of the allocator.
   */
  public synchronized <X extends Poolable> Config<X> setAllocator(
      Allocator<X> allocator) {
    this.allocator = allocator;
    return (Config<X>) this;
  }

  /**
   * Get the configured {@link Allocator} instance. There is no configured
   * allocator by default, so this must be {@link #setAllocator(Allocator) set}
   * before instantiating any Pool implementations from this Config.
   * @return The configured Allocator instance, if any.
   */
  public synchronized Allocator<T> getAllocator() {
    return (Allocator<T>) allocator;
  }

  /**
   * Set the {@link Expiration} to use for the pools we want to
   * configure. The Expiration determines when a pooled object is valid
   * for claiming, or when the objects are invalid and should be deallocated.
   * <p>
   * The default Expiration is a {@link TimeExpiration} that
   * invalidates the objects after they have been active for 10 minutes.
   * @param expiration
   * @return
   */
  public synchronized Config<T> setExpiration(Expiration<? super T> expiration) {
    this.expiration = expiration;
    return this;
  }

  public synchronized Expiration<? super T> getExpiration() {
    return expiration;
  }

  /**
   * Check that the configuration is valid. This method is useful in the
   * Pool implementation constructors.
   * @throws IllegalArgumentException If the size is less than one, if the TTL
   * value is less than one, if the TTL {@link TimeUnit} is <code>null</code>,
   * or if the {@link Allocator} is <code>null</code>.
   */
  public synchronized void validate() throws IllegalArgumentException {
    if (size < 1) {
      throw new IllegalArgumentException(
          "size must be at least 1, but was " + size);
    }
    if (allocator == null) {
      throw new IllegalArgumentException(
          "Allocator cannot be null");
    }
    if (expiration == null) {
      throw new IllegalArgumentException("Expiration cannot be null");
    }
  }
}
