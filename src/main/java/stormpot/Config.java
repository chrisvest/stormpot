package stormpot;

public class Config {

  private int size = 10;

  public synchronized Config copy() {
    Config config = new Config();
    return config;
  }

  public synchronized Config setSize(int size) {
    this.size = size;
    return this;
  }

  public synchronized int getSize() {
    return size;
  }
}
