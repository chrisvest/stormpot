package stormpot.benchmark;

import java.util.concurrent.TimeUnit;

import stormpot.Config;
import stormpot.Timeout;
import stormpot.qpool.QueuePool;

public class QueuePoolBench extends Bench {
  private static final Timeout timeout =
      new Timeout(1000, TimeUnit.MILLISECONDS);
  
  private QueuePool<StormpotPoolable> pool;

  @Override
  public void primeWithSize(int size) {
    Config<StormpotPoolable> config = new Config<StormpotPoolable>();
    config.setAllocator(new StormpotPoolableAllocator());
    config.setSize(size);
    pool = new QueuePool<StormpotPoolable>(config);
  }

  @Override
  public Object claim() throws Exception {
    return pool.claim(timeout);
  }

  @Override
  public void release(Object object) throws Exception {
    ((StormpotPoolable) object).release();
  }

  @Override
  public void claimAndRelease() throws Exception {
    release(claim());
  }
}
