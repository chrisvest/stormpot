package stormpot;

import java.util.concurrent.TimeUnit;

/**
 * The basic configuration "bean" class.
 * <p>
 * Instances of this class is passed to the constructors of {@link Pool pools}
 * so that they know how big they should be, how to allocate objects and for
 * how long they should live.
 * <p>
 * This class is made thread-safe by having the fields be protected by a lock.
 * The details of this locking mechanism should not be relied upon by client
 * code.
 * <p>
 * The various set* methods are made to return the Config instance itself, so
 * that the method calls may be chained if so desired.
 * @author Chris Vest &lt;mr.chrisvest@gmail.com&gt;
 * @param <T> The type of {@link Poolable} objects that a {@link Pool} based
 * on this Config will produce.
 */
@SuppressWarnings("unchecked")
public class Config<T extends Poolable> {

  private int size = 10;
  private long ttl = 10;
  private TimeUnit ttlUnit = TimeUnit.MINUTES;
  private Allocator allocator;

  /**
   * Set the size of the pools we want to configure them with.
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
   * @param ttl The scalar value of the time-to-live value. Must be at least 1.
   * @param unit The unit of the 'ttl' value. Cannot be null.
   * @return This Config instance.
   */
  public synchronized Config<T> setTTL(long ttl, TimeUnit unit) {
    this.ttl = ttl;
    this.ttlUnit = unit;
    return this;
  }

  /**
   * Get the scalar value of the time-to-live value that applies to the objects
   * in the pools we want to configure. Default TTL is 10 minutes.
   * @return The scalar time-to-live.
   */
  public synchronized long getTTL() {
    return ttl;
  }

  /**
   * Get the time-scale unit of the configured time-to-live that applies to the
   * objects in the pools we want to configure. Default TTL is 10 minutes.
   * @return The time-scale unit of the configured time-to-live value.
   */
  public synchronized TimeUnit getTTLUnit() {
    return ttlUnit;
  }

  /**
   * Set the {@link Allocator} to use for the pools we want to configure.
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
    return allocator;
  }

  /**
   * Configure the Config instance given as a parameter, with all the values
   * of this Config.
   * @param config The Config instance whose allocator, size and TTL we want
   * to configure such that they match our configuration.
   */
  public synchronized void setFieldsOn(Config config) {
    config.setAllocator(allocator);
    config.setSize(size);
    config.setTTL(ttl, ttlUnit);
  }
}
