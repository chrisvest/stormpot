package stormpot;

import java.util.concurrent.TimeUnit;

public class Config<T extends Poolable> {

  private int size = 10;
  private long ttl = 10;
  private TimeUnit ttlUnit = TimeUnit.MINUTES;
  private Allocator allocator;

  public synchronized Config<T> setSize(int size) {
    this.size = size;
    return this;
  }

  public synchronized int getSize() {
    return size;
  }

  public synchronized Config<T> setTTL(long ttl, TimeUnit unit) {
    this.ttl = ttl;
    this.ttlUnit = unit;
    return this;
  }

  public synchronized long getTTL() {
    return ttl;
  }

  public synchronized TimeUnit getTTLUnit() {
    return ttlUnit;
  }

  public synchronized <X extends Poolable> Config<X> setAllocator(
      Allocator<X> allocator) {
    this.allocator = allocator;
    return (Config<X>) this;
  }

  public synchronized Allocator<T> getAllocator() {
    return allocator;
  }

  public synchronized void setFieldsOn(Config config) {
    config.setAllocator(allocator);
    config.setSize(size);
    config.setTTL(ttl, ttlUnit);
  }
}
