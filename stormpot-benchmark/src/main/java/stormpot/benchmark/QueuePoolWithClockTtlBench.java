package stormpot.benchmark;

import java.util.concurrent.TimeUnit;

import stormpot.Config;
import stormpot.Expiration;
import stormpot.SlotInfo;
import stormpot.Timeout;
import stormpot.qpool.QueuePool;

public class QueuePoolWithClockTtlBench extends Bench {
  private static final Timeout timeout =
      new Timeout(1000, TimeUnit.MILLISECONDS);
  
  private QueuePool<MyPoolable> pool;

  @Override
  public void primeWithSize(int size, final long objTtlMillis) {
    Config<MyPoolable> config = new Config<MyPoolable>();
    config.setAllocator(new StormpotPoolableAllocator());
    config.setSize(size);
    config.setExpiration(new Expiration<MyPoolable>() {
      @Override
      public boolean hasExpired(SlotInfo<? extends MyPoolable> info) {
        return info.getPoolable().olderThan(objTtlMillis);
      }
    });
    pool = new QueuePool<MyPoolable>(config);
  }

  @Override
  public Object claim() throws Exception {
    return pool.claim(timeout);
  }

  @Override
  public void release(Object object) throws Exception {
    ((MyPoolable) object).release();
  }

  @Override
  public String getName() {
    return pool.getClass().getSimpleName() + "-FastExpire";
  }
}
