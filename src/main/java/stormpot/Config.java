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

import java.util.concurrent.TimeUnit;

/**
 * The basic configuration "bean" class.
 * <p>
 * Instances of this class is passed to the constructors of {@link Pool pools}
 * so that they know how big they should be, how to allocate objects and when
 * to deallocate objects.
 * <p>
 * This class is made thread-safe by having the fields be protected by the
 * intrinsic object lock on the Config object itself. This way, pools can
 * <code>synchronize</code> on the config object to read the values out
 * atomically.
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
      new TimeSpreadExpiration(480000, 600000, TimeUnit.MILLISECONDS); // 8 to 10 minutes
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
   * The default Expiration is a {@link TimeSpreadExpiration} that
   * invalidates the objects after they have been active for somewhere between
   * 8 to 10 minutes.
   * @param expiration The expiration we want our pools to use. Not null.
   * @return This Config instance.
   */
  public synchronized Config<T> setExpiration(Expiration<? super T> expiration) {
    this.expiration = expiration;
    return this;
  }

  /**
   * Get the configured {@link Expiration} instance. The default is a
   * {@link TimeSpreadExpiration} that expires objects after somewhere between
   * 8 to 10 minutes.
   * @return The configured Expiration.
   */
  public synchronized Expiration<? super T> getExpiration() {
    return expiration;
  }

  /**
   * Check that the configuration is valid in terms of the <em>standard
   * configuration</em>. This method is useful in the
   * Pool implementation constructors.
   * @throws IllegalArgumentException If the size is less than one, if the
   * {@link Expiration} is <code>null</code>, or if the {@link Allocator} is
   * <code>null</code>.
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
