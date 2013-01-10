package stormpot.qpool;

import java.util.concurrent.BlockingQueue;

import stormpot.AllocThread_ShutdownNullsPool_TestTemplate;
import stormpot.Poolable;

public class QAllocThread_ShutdownNullsPoll_Test
extends AllocThread_ShutdownNullsPool_TestTemplate<QSlot<Poolable>, QAllocThread<Poolable>> {

  @Override
  protected QAllocThread<Poolable> createAllocThread(
      BlockingQueue<QSlot<Poolable>> live, BlockingQueue<QSlot<Poolable>> dead) {
    return new QAllocThread<Poolable>(live, dead, config);
  }

  @Override
  protected QSlot<Poolable> createSlot(BlockingQueue<QSlot<Poolable>> live) {
    return new QSlot<Poolable>(live);
  }
}
