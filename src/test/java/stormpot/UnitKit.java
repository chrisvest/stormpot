/*
 * Copyright 2011 Chris Vest
 * 
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 * 
 *      http://www.apache.org/licenses/LICENSE-2.0
 * 
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package stormpot;

import static org.hamcrest.Matchers.*;
import static org.junit.Assert.*;
import static org.junit.Assume.*;

import java.lang.Thread.State;
import java.util.concurrent.Callable;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.locks.LockSupport;

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
        try {
          return pool.claim();
        } catch (RuntimeException e) {
          throw e;
        } catch (InterruptedException e) {
          throw new PoolException("claim interrupted", e);
        }
      }
    };
  }

  public static Callable<Poolable> $claim(
      final Pool pool, final long timeout, final TimeUnit unit) {
    return new Callable<Poolable>() {
      public Poolable call() {
        try {
          return pool.claim(timeout, unit);
        } catch (RuntimeException e) {
          throw e;
        } catch (InterruptedException e) {
          throw new PoolException("claim interrupted", e);
        }
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
      final Callable<T> procedure, final AtomicReference<Exception> caught) {
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
  
  public static Callable $interruptUponState(
      final Thread thread, final Thread.State state) {
    return new Callable() {
      public Object call() throws Exception {
        waitForThreadState(thread, state);
        thread.interrupt();
        return null;
      }
    };
  }
  
  public static Callable $delayedReleases(
      final Poolable[] objs, long delay, TimeUnit delayUnit) {
    final long deadline =
      System.currentTimeMillis() + delayUnit.toMillis(delay);
    return new Callable() {
      public Object call() throws Exception {
        for (Poolable obj : objs) {
          LockSupport.parkUntil(deadline);
          obj.release();
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

  public static void spinwait(long ms) {
    long now = System.currentTimeMillis();
    long deadline = now + ms;
    while (now < deadline) {
      now = System.currentTimeMillis();
    }
  }
}
