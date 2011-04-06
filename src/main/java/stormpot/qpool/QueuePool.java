package stormpot.qpool;

import java.util.concurrent.BlockingQueue;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.locks.LockSupport;

import stormpot.Allocator;
import stormpot.Completion;
import stormpot.Config;
import stormpot.LifecycledPool;
import stormpot.PoolException;
import stormpot.Poolable;
import stormpot.Slot;

public class QueuePool<T extends Poolable> implements LifecycledPool<T> {
  private static final QSlot killPill = new QSlot(null);
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
    if (slot == killPill) {
      live.offer(killPill);
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
      slot = live.poll(timeout, unit);
    } while (invalid(slot));
    return slot == null? null : slot.obj;
  }

  public Completion shutdown() {
    shutdown = true;
    live.offer(killPill);
    allocThread.interrupt();
    return new Completion() {
      public void await() throws InterruptedException {
        allocThread.completionLatch.await();
      }

      public boolean await(long timeout, TimeUnit unit)
          throws InterruptedException {
        return allocThread.completionLatch.await(timeout, unit);
      }
    };
  }

  private static class QSlot<T extends Poolable> implements Slot {
    final BlockingQueue<QSlot<T>> live;
    final AtomicBoolean claimed;
    T obj;
    Exception poison;
    long expires;
    
    public QSlot(BlockingQueue<QSlot<T>> live) {
      this.live = live;
      this.claimed = new AtomicBoolean();
    }
    
    public void claim() {
      claimed.set(true);
    }

    public boolean expired() {
      return expires < System.currentTimeMillis();
    }
    
    public void release() {
      if (claimed.compareAndSet(true, false)) {
        live.offer(this);
      }
    }
  }
  
  private static class QAllocThread<T extends Poolable> extends Thread {
    final CountDownLatch completionLatch;
    private final BlockingQueue<QSlot<T>> live;
    private final BlockingQueue<QSlot<T>> dead;
    private final Allocator<T> allocator;
    private final int targetSize;
    private final long ttlMillis;
    private int size;

    public QAllocThread(
        BlockingQueue<QSlot<T>> live, BlockingQueue<QSlot<T>> dead,
        Config<T> config) {
      this.targetSize = config.getSize();
      if (targetSize < 1) {
        throw new IllegalArgumentException("size must be at least 1");
      }
      completionLatch = new CountDownLatch(1);
      this.allocator = config.getAllocator();
      this.size = 0;
      this.live = live;
      this.dead = dead;
      ttlMillis = config.getTTLUnit().toMillis(config.getTTL());
    }

    @Override
    public void run() {
      try {
        for (;;) {
          if (size < targetSize) {
            QSlot slot = new QSlot(live);
            alloc(slot);
          }
          QSlot slot = dead.poll(50, TimeUnit.MILLISECONDS);
          if (slot != null) {
            dealloc(slot);
            alloc(slot);
          }
        }
      } catch (InterruptedException e) {
        // we're shut down
        while (size > 0) {
          QSlot<T> slot = dead.poll();
          if (slot == null) {
            slot = live.poll();
          }
          if (slot == killPill) {
            live.offer(killPill);
            slot = null;
          }
          if (slot == null) {
            LockSupport.parkNanos(10000000); // 10 millis
          } else {
            dealloc(slot);
          }
        }
      } finally {
        completionLatch.countDown();
      }
    }

    private void alloc(QSlot slot) {
      try {
        slot.obj = allocator.allocate(slot);
        if (slot.obj == null) {
          slot.poison = new NullPointerException("allocation returned null");
        } else {
        }
      } catch (Exception e) {
        slot.poison = e;
      }
      size++;
      slot.expires = System.currentTimeMillis() + ttlMillis;
      slot.claim();
      slot.release();
    }

    private void dealloc(QSlot<T> slot) {
      size--;
      try {
        if (slot.poison == null) {
          allocator.deallocate(slot.obj);
        }
      } catch (Exception _) {
        // ignored as per specification
      } finally {
        slot.poison = null;
        slot.obj = null;
      }
    }
  }
}
