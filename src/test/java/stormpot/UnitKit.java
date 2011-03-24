package stormpot;

import static org.hamcrest.Matchers.*;
import static org.junit.Assert.*;
import static org.junit.Assume.*;

import java.lang.Thread.State;
import java.util.concurrent.Callable;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

public class UnitKit {

  public static Thread fork(Callable procedure) {
    Thread thread = new Thread(asRunnable(procedure));
    thread.start();
    return thread;
  }

  public static Runnable asRunnable(final Callable procedure) {
    return new Runnable() {
      public void run() {
        try {
          procedure.call();
        } catch (Exception e) {
          throw new RuntimeException(e);
        }
      }
    };
  }

  public static Callable<Poolable> $claim(final Pool pool) {
    return new Callable<Poolable>() {
      public Poolable call() {
        return pool.claim();
      }
    };
  }
  
  public static Callable<Completion> $await(final Completion completion) {
    return new Callable<Completion>() {
      public Completion call() throws Exception {
        completion.await();
        return completion;
      }
    };
  }
  
  public static Callable<AtomicBoolean> $await(
      final Completion completion, final long timeout,
      final TimeUnit unit, final AtomicBoolean result) {
    return new Callable<AtomicBoolean>() {
      public AtomicBoolean call() throws Exception {
        result.set(completion.await(timeout, unit));
        return result;
      }
    };
  }
  
  public static <T> Callable<T> $catchFrom(
      final Callable<T> procedure, final AtomicReference caught) {
    return new Callable<T>() {
      public T call() {
        try {
          return procedure.call();
        } catch (Exception e) {
          caught.set(e);
        }
        return null;
      }
    };
  }
  
  public static void waitForThreadState(Thread thread, Thread.State targetState) {
    State currentState = thread.getState();
    while (currentState != targetState) {
      assertThat(currentState, is(not(Thread.State.TERMINATED)));
      Thread.yield();
      currentState = thread.getState();
    }
  }
  
  public static void join(Thread thread) {
    try {
      thread.join();
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
    }
  }

  public static Completion shutdown(Pool pool) {
    assumeThat(pool, instanceOf(LifecycledPool.class));
    return ((LifecycledPool) pool).shutdown();
  }

}
