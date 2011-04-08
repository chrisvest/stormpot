package stormpot.benchmark;

import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.LockSupport;

import stormpot.Allocator;
import stormpot.Config;
import stormpot.GenericPoolable;
import stormpot.LifecycledPool;
import stormpot.Pool;
import stormpot.PoolFixtures;
import stormpot.Poolable;
import stormpot.Slot;

import com.google.caliper.Param;
import com.google.caliper.SimpleBenchmark;

@SuppressWarnings("unchecked")
public class PoolSpin extends SimpleBenchmark {
  private final class SlowAllocator implements Allocator {
    private final long workMs;

    public SlowAllocator(long workMs) {
      this.workMs = workMs;
    }
    
    public Poolable allocate(Slot slot) {
      long deadline = System.currentTimeMillis() + workMs;
      while (System.currentTimeMillis() < deadline); // spin
      return new GenericPoolable(slot);
    }

    public void deallocate(Poolable poolable) {
    }
  }

  @Param int size = 10;
  @Param long work = 10;
  @Param int poolType = 0;
  @Param long ttl = 10000;

  protected Pool pool;
  
  @Override
  protected void setUp() throws Exception {
    Config config = new Config();
    config.setAllocator(new SlowAllocator(work));
    config.setSize(size);
    config.setTTL(ttl, TimeUnit.MILLISECONDS);
    pool = PoolFixtures.poolFixtures()[poolType].initPool(config);
    // Give the pool 50 ms to boot up any threads it might need
    LockSupport.parkNanos(50000000);
  }
  
  public int timeClaimReleaseSpin(int reps) throws Exception {
    int result = 1235789;
    for (int i = 0; i < reps; i++) {
      Poolable obj = pool.claim();
      result ^= obj.hashCode();
      obj.release();
    }
    return result;
  }

  @Override
  protected void tearDown() throws Exception {
    if (pool instanceof LifecycledPool) {
      LifecycledPool p = (LifecycledPool) pool;
      p.shutdown().await();
    }
  }
}
