package stormpot.bpool;

import java.util.concurrent.BlockingQueue;

import stormpot.AllocThread_ShutdownNullsPool_TestTemplate;
import stormpot.Poolable;

public class BAllocThread_ShutdownNullsPoll_Test
extends AllocThread_ShutdownNullsPool_TestTemplate<BSlot<Poolable>, BAllocThread<Poolable>>{

  @Override
  protected BAllocThread<Poolable> createAllocThread(
      BlockingQueue<BSlot<Poolable>> live, BlockingQueue<BSlot<Poolable>> dead) {
    return new BAllocThread<Poolable>(live, dead, config);
  }

  @Override
  protected BSlot<Poolable> createSlot(BlockingQueue<BSlot<Poolable>> live) {
    return new BSlot<Poolable>(live);
  }
}
