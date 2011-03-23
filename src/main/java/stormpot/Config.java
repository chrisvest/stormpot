package stormpot;

public class Config {

  private int size = 10;
  private boolean sane = true;

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

  public synchronized Config goInsane() {
    sane = false;
    return this;
  }
}
