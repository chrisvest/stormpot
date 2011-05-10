package stormpot.whirlpool;

import static java.util.concurrent.atomic.AtomicReferenceFieldUpdater.*;
import static java.util.concurrent.atomic.AtomicIntegerFieldUpdater.*;

import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicIntegerFieldUpdater;
import java.util.concurrent.atomic.AtomicReferenceFieldUpdater;

import stormpot.Completion;
import stormpot.Config;
import stormpot.LifecycledPool;
import stormpot.Pool;
import stormpot.PoolException;
import stormpot.Poolable;

/*
 * 1. Write the invocation opcode and parameters[1] (if any) of the method m to
 *    be applied sequentially to the shared object in the request field of your
 *    thread local publication record (there is no need to use a load-store
 *    memory barrier). The request field will later be used to receive[2] the
 *    response. If your thread local publication record is marked as active
 *    continue to step 2, otherwise continue to step 5.
 * 
 * 2. Check if the global lock is taken. If so (another thread is an active
 *    combiner), spin on the request field waiting for a response to the
 *    invocation (one can add a yield at this point to allow other threads on
 *    the same core to run). Once in a while, while spinning, check if the lock
 *    is still taken and that your record is active. If your record is
 *    inactive proceed to step 5. Once the response is available, reset the
 *    request field to null and return the response.
 * 
 * 3. If the lock is not taken, attempt to acquire it and become a combiner.
 *    If you fail, return to spinning in step 2.
 * 
 * 4. Otherwise, you hold the lock and are a combiner.
 *    • Increment the combining pass count by one.
 *    • Execute a scanCombineApply() by traversing the publication list from
 *      the head, combining all non-null method call invocations, setting the
 *      age of each of these records to the current count, applying the
 *      combined method calls to the structure D, and returning responses to
 *      all the invocations. As we explain later, this traversal is
 *      guaranteed to be wait-free.
 *    • If the count is such that a cleanup needs to be performed, traverse
 *      the publication list from the head. Starting from the second item (as
 *      we explain below, we always leave the item pointed to by the head in
 *      the list), remove from the publication list all records whose age is
 *      much smaller than the current count. This is done by removing the node
 *      and marking it as inactive.
 *    • Release the lock.
 * 
 * 5. If you have no thread local publication record allocate one, marked as
 *    active. If you already have one marked as inactive, mark it as active.
 *    Execute a store-load memory barrier. Proceed to insert the record into
 *    the list with a successful CAS to the head. Then proceed to step 1.
 * 
 * [1]: This must be a single atomic write, in order to preserve correct
 * ordering. Otherwise, the combiner thread might see the opcode before the
 * parameters are written.
 * In our case, we need to support 3 operations: Release a slot, claim a slot
 * and retrieve a dead slot. We encode the opcode by the type of the object in
 * the request field. If the object is a Slot, then we are releasing it. If
 * the request field is the "claim" value of the Take enum, then we are doing
 * a claim. If the request field is the "relieve" value of the Take enum, then
 * we are removing a dead slot from the queue for the purpose of reallocating
 * it. Otherwise, if the request field is null, then we take no action.
 * 
 * [2]: We are going to have a separate response field for this. The combiner
 * thread will write a null to the request field, once it has completed the
 * request. And the requester will write a null to the response field once it
 * has read it. The "claim" and "relieve" methods will both receive a slot
 * reference in the response field when the combiner thread has completed their
 * requests. A release call will receive a non-null value.
 */
/**
 * Whirlpool is a {@link Pool} that is based on a queue-like structure, made
 * concurrent using the Flat-Combining technique of Hendler, Incze, Shavit and
 * Tzafrir.
 * @author Chris Vest &lt;mr.chrisvest@gmail.com&gt;
 * @param <T> The type of {@link Poolable} managed by this pool.
 *
 */
public class Whirlpool<T extends Poolable> implements LifecycledPool<T> {
  static final int LOCKED = 1;
  static final int UNLOCKED = 0;
  static final int WAIT_SPINS = 128;
  static final int CLEANUP_MASK = (1 << 12) - 1;
  static final int PARK_TIME_NS = 1000000;
  static final int EXPIRE_PASS_COUNT = 100;

  static final WSlot CLAIM = new WSlot(null);
  static final WSlot RELIEVE = new WSlot(null);
  static final WSlot RELEASE = new WSlot(null);
  static final WSlot KILLPILL = new WSlot(null);
  
  static final AtomicReferenceFieldUpdater<Whirlpool, Request> publistCas =
    newUpdater(Whirlpool.class, Request.class, "publist");
  static final AtomicIntegerFieldUpdater<Whirlpool> lockCas =
    newUpdater(Whirlpool.class, "lock");
  
  private final RequestThreadLocal requestTL = new RequestThreadLocal();
  
  private volatile Request publist;
  @SuppressWarnings("unused")
  private volatile int lock = UNLOCKED;
  private volatile boolean shutdown = false;
  private int combiningPass;
  private WSlot liveStack;
  private WSlot deadStack;
  private long ttl;
  private WpAllocThread alloc;
  
  /**
   * Construct a new Whirlpool instance from the given {@link Config}.
   * @param config The pool configuration to use.
   */
  public Whirlpool(Config<T> config) {
    synchronized (config) {
      config.validate();
      ttl = config.getTTLUnit().toMillis(config.getTTL());
      alloc = new WpAllocThread(config, this);
    }
    alloc.start();
  }

  WSlot relieve(long timeout, TimeUnit unit) throws InterruptedException {
    Request request = requestTL.get();
    request.setTimeout(timeout, unit);
    request.requestOp = RELIEVE;
    return perform(request, false, true);
  }
  
  public T claim() throws PoolException, InterruptedException {
    Request request = requestTL.get();
    request.setNoTimeout();
    request.requestOp = CLAIM;
    WSlot slot = perform(request, true, true);
    return objectOf(slot);
  }

  @SuppressWarnings("unchecked")
  public T claim(long timeout, TimeUnit unit) throws PoolException,
      InterruptedException {
    Request request = requestTL.get();
    request.setTimeout(timeout, unit);
    request.requestOp = CLAIM;
    WSlot slot = perform(request, true, true);
    return objectOf(slot);
  }

  private T objectOf(WSlot slot) {
    if (slot == null) {
      return null;
    }
    if (slot.poison != null) {
      Exception exception = slot.poison;
      slot.created = 0;
      release(slot);
      throw new PoolException("allocation failed", exception);
    }
    if (slot == KILLPILL) {
      throw new IllegalStateException("pool is shutdown");
    }
    slot.claimed = true;
    return (T) slot.obj;
  }

  void release(WSlot slot) {
    Request request = requestTL.get();
    request.setTimeout(1, TimeUnit.HOURS);
    request.requestOp = slot;
    try {
      perform(request, false, false);
    } catch (InterruptedException e) {
      // this is not possible, but regardless...
      Thread.currentThread().interrupt();
    }
  }

  private WSlot perform(
      Request request, boolean checkShutdown, boolean interruptible)
  throws InterruptedException {
    for (;;) {
      if (checkShutdown && shutdown) {
        throw new IllegalStateException("pool is shut down");
      }
      if (interruptible && Thread.interrupted()) {
        throw new InterruptedException();
      }
      if (request.active) {
        // step 2
        if (lockCas.compareAndSet(this, UNLOCKED, LOCKED)) { // step 3
          // step 4 - got lock - we are now a combiner
          combiningPass++;
          scanCombineApply();
          if ((combiningPass & CLEANUP_MASK) == CLEANUP_MASK) {
            cleanUp();
          }
          lock = UNLOCKED;
          WSlot response = request.response;
          if (response == null) {
            if (!request.await()) {
              return null;
            }
            continue;
          }
          request.response = null;
          return response;
        } else {
          // step 2 - did not get lock - spin-wait for response
          for (int i = 0; i < WAIT_SPINS; i++) {
            WSlot slot = request.response;
            if (slot != null) {
              request.response = null;
              return slot;
            }
          }
          if (!request.await()) {
            return null;
          }
          continue;
        }
      } else {
        // step 5 - reactivate request and insert into publist
        activate(request);
      }
    }
  }

  private void scanCombineApply() {
    // traverse publist, combine ops & set "age" on requests
    long now = System.currentTimeMillis();
    Request current = publist;
    boolean shutdown = this.shutdown;
    while (current != null) {
      WSlot op = current.requestOp;
      if (op == CLAIM) {
        // a claim request
        // TODO optimize when claim comes before release on a depleted pool
        if (shutdown) {
          replyTo(current, KILLPILL);
        } else if (liveStack != null) {
          WSlot prospect = liveStack;
          liveStack = prospect.next;
          if (expired(prospect, now)) {
            prospect.next = deadStack;
            deadStack = prospect;
            continue;
          }
          replyTo(current, prospect);
        }
      } else if (op == RELIEVE) {
        // a relieve (reallocate) request
        WSlot response = deadStack;
        if (response != null) {
          deadStack = response.next;
          replyTo(current, response);
        } else if (shutdown && liveStack != null) {
          response = liveStack;
          liveStack = response.next;
          replyTo(current, response);
        }
      } else if (op != null) {
        // a release request
        if (expired(op, now)) {
          op.next = deadStack;
          deadStack = op;
        } else {
          op.next = liveStack;
          liveStack = op;
        }
        replyTo(current, RELEASE);
      }
      current = current.next;
    }
  }

  private boolean expired(WSlot prospect, long now) {
    return prospect.created + ttl < now;
  }

  private void replyTo(Request request, WSlot response) {
    request.requestOp = null;
    request.response = response;
    request.passCount = combiningPass;
    request.unpark();
  }

  private void cleanUp() {
    // Called when the combiningPass count say it's time
    Request current = publist;
    // initial 'current' value is never null because publist at this point is
    // guaranteed to contain at least one Request object - namely our own.
    while (current.next != null) {
      if (expired(current.next) && current.requestOp == null) {
        current.next.active = false;
        current.next = current.next.next;
      } else {
        current = current.next;
      }
    }
  }

  private boolean expired(Request request) {
    return combiningPass - request.passCount > EXPIRE_PASS_COUNT;
  }

  private void activate(Request request) {
    request.active = true;
    do {
      request.next = publist;
    } while (!publistCas.compareAndSet(this, request.next, request));
  }

  public Completion shutdown() {
    alloc.shutdown();
    shutdown = true;
    return alloc;
  }
}
