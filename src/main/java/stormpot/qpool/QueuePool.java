package stormpot.qpool;

import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;

import stormpot.Completion;
import stormpot.Config;
import stormpot.LifecycledPool;
import stormpot.PoolException;
import stormpot.Poolable;

/**
 * QueuePool is a fairly simple {@link LifecycledPool} implementation that
 * basically consists of a queue of Poolable instances, and a Thread to
 * allocate them.
 * <p>
 * This means that the object allocation always happens in a dedicated thread.
 * This means that no thread that calls any of the claim methods, will incur
 * the overhead of allocating Poolables. This should lead to reduced deviation
 * in the times it takes claim method to complete, provided the pool is not
 * depleted.
 * @author Chris Vest &lt;mr.chrisvest@gmail.com&gt;
 *
 * @param <T>
 */
@SuppressWarnings("unchecked")
public final class QueuePool<T extends Poolable> implements LifecycledPool<T> {
  static final QSlot KILL_PILL = new QSlot(null);
  private final BlockingQueue<QSlot<T>> live;
  private final BlockingQueue<QSlot<T>> dead;
  private final QAllocThread allocThread;
  private volatile boolean shutdown = false;
  
  public QueuePool(Config config) {
    live = new LinkedBlockingQueue<QSlot<T>>();
    dead = new LinkedBlockingQueue<QSlot<T>>();
    allocThread = new QAllocThread(live, dead, config);
    allocThread.start();
  }

  public T claim() throws PoolException, InterruptedException {
    QSlot<T> slot;
    do {
      slot = live.take();
    } while (invalid(slot));
    return slot.obj;
  }

  private boolean invalid(QSlot<T> slot) {
    if (slot == null) {
      return false;
    }
    if (slot == KILL_PILL) {
      live.offer(KILL_PILL);
      throw new IllegalStateException("pool is shut down");
    }
    if (slot.poison != null) {
      dead.offer(slot);
      throw new PoolException("allocation failed", slot.poison);
    }
    if (slot.expired()) {
      dead.offer(slot);
      return true;
    }
    if (shutdown) {
      dead.offer(slot);
      throw new IllegalStateException("pool is shut down");
    }
    slot.claim();
    return false;
  }

  public T claim(long timeout, TimeUnit unit) throws PoolException,
      InterruptedException {
    QSlot<T> slot;
    do {
      // TODO timeout-reset bug
      slot = live.poll(timeout, unit);
    } while (invalid(slot));
    return slot == null? null : slot.obj;
  }

  public Completion shutdown() {
    shutdown = true;
    live.offer(KILL_PILL);
    allocThread.interrupt();
    return new QPoolShutdownCompletion(allocThread);
  }
}
