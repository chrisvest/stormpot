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

import java.lang.Thread.State;
import java.util.concurrent.Callable;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.locks.LockSupport;

import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.not;
import static org.junit.Assert.assertThat;

class UnitKit {
  private static final ExecutorService executor = Executors.newCachedThreadPool();
  
  public static Thread fork(Callable<?> procedure) {
    Thread thread = new Thread(asRunnable(procedure));
    thread.start();
    return thread;
  }
  
  public static <T> Future<T> forkFuture(Callable<T> proceduce) {
    return executor.submit(proceduce);
  }

  public static Runnable asRunnable(final Callable<?> procedure) {
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

  public static <T extends Poolable> Callable<T> $claim(
      final Pool<T> pool, final Timeout timeout) {
    return new Callable<T>() {
      public T call() {
        try {
          return pool.claim(timeout);
        } catch (InterruptedException e) {
          throw new PoolException("claim interrupted", e);
        }
      }
    };
  }
  
  public static <T> Callable<T> capture(
      final Callable<T> callable,
      final AtomicReference<T> ref) {
    return new Callable<T>() {
      @Override
      public T call() throws Exception {
        T obj = callable.call();
        ref.set(obj);
        return obj;
      }
    };
  }
  
  public static Callable<Completion> $await(
      final Completion completion,
      final Timeout timeout) {
    return new Callable<Completion>() {
      public Completion call() throws Exception {
        completion.await(timeout);
        return completion;
      }
    };
  }
  
  public static Callable<AtomicBoolean> $await(
      final Completion completion,
      final Timeout timeout,
      final AtomicBoolean result) {
    return new Callable<AtomicBoolean>() {
      public AtomicBoolean call() throws Exception {
        result.set(completion.await(timeout));
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
  
  public static Callable<Void> $interruptUponState(
      final Thread thread, final Thread.State state) {
    return new Callable<Void>() {
      public Void call() throws Exception {
        waitForThreadState(thread, state);
        thread.interrupt();
        return null;
      }
    };
  }
  
  public static Callable<Void> $delayedReleases(
      final Poolable[] objs, long delay, TimeUnit delayUnit) {
    final long deadline =
      System.currentTimeMillis() + delayUnit.toMillis(delay);
    return new Callable<Void>() {
      public Void call() throws Exception {
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
  
  public static void join(Future<?> future) {
    try {
      future.get();
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
    } catch (ExecutionException e) {
      // AssertionError doesn't have the (String, Throwable) constructor :(
      throw new Error("Unexpected exception", e);
    }
  }

  public static void spinwait(long ms) {
    long now = System.currentTimeMillis();
    long deadline = now + ms;
    while (now < deadline) {
      now = System.currentTimeMillis();
    }
  }
}
