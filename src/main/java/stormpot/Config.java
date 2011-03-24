package stormpot;

import java.util.concurrent.TimeUnit;

public class Config {

  private int size = 10;
  private boolean sane = true;
  private long ttl = 10;
  private TimeUnit ttlUnit = TimeUnit.MINUTES;

  public synchronized Config copy() {
    Config config = new Config();
    config.setSize(size);
    return config;
  }

  public synchronized Config setSize(int size) {
    if (sane && size < 1) {
      throw new IllegalArgumentException(
          "size must be at least 1 but was " + size);
    }
    this.size = size;
    return this;
  }

  public synchronized int getSize() {
    return size;
  }

  synchronized Config goInsane() {
    sane = false;
    return this;
  }

  public synchronized Config setTTL(long ttl, TimeUnit unit) {
    if (sane && unit == null) {
      throw new IllegalArgumentException("unit cannot be null");
    }
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
}
