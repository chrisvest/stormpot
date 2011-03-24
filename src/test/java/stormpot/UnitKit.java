package stormpot;

import static org.hamcrest.Matchers.*;
import static org.junit.Assert.*;
import static org.junit.Assume.*;

import java.lang.Thread.State;
import java.util.concurrent.Callable;
import java.util.concurrent.Future;

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
