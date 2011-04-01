package stormpot.whirlpool;

import stormpot.Config;

public class WhirlpoolConfig extends Config {

  private int partitions;

  public WhirlpoolConfig(Config config) {
    config.setFieldsOn(this);
  }

  public synchronized int getPartitionCount() {
    return partitions;
  }
  
  public synchronized void setPartitionCount(int count) {
    this.partitions = count;
  }

  @Override
  public synchronized void setFieldsOn(Config config) {
    super.setFieldsOn(config);
    if (config instanceof WhirlpoolConfig) {
      WhirlpoolConfig cfg = (WhirlpoolConfig) config;
      cfg.setPartitionCount(partitions);
    }
  }

  @Override
  public synchronized Config setSize(int size) {
    if (partitions == 0) {
      int cores = Runtime.getRuntime().availableProcessors();
      partitions = Math.min(size, cores);
    }
    return super.setSize(size);
  }
}
