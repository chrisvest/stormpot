/*
 * Copyright © 2011-2024 Chris Vest (mr.chrisvest@gmail.com)
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package testkits;

import stormpot.Completion;
import stormpot.Pool;
import stormpot.PoolException;
import stormpot.PoolTap;
import stormpot.Poolable;
import stormpot.Timeout;

import java.io.Serial;
import java.lang.Thread.State;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;

public class UnitKit {
  private static final ExecutorService executor =
      Executors.newCachedThreadPool();
  
  public static Thread fork(Callable<?> procedure) {
    Thread thread = new Thread(asRunnable(procedure));
    thread.setUncaughtExceptionHandler(new CatchingExceptionHandler());
    thread.start();
    return thread;
  }

  private static Runnable asRunnable(final Callable<?> procedure) {
    return () -> {
      try {
        procedure.call();
      } catch (Exception e) {
        throw new WrappedException(e);
      }
    };
  }

  private static class WrappedException extends RuntimeException {
    @Serial
    private static final long serialVersionUID = 8268471823070464895L;

    WrappedException(Throwable cause) {
      super(cause);
    }
  }

  private static class CatchingExceptionHandler
      extends AtomicReference<Throwable>
      implements Thread.UncaughtExceptionHandler {

    @Serial
    private static final long serialVersionUID = 2170391393239672337L;

    @Override
    public void uncaughtException(Thread t, Throwable e) {
      if (e instanceof WrappedException) {
        set(e.getCause());
      } else {
        set(e);
      }
    }
  }

  public static AtomicReference<Throwable> capture(Thread thread) {
    Thread.UncaughtExceptionHandler handler = thread.getUncaughtExceptionHandler();
    if (handler instanceof CatchingExceptionHandler catcher) {
      return catcher;
    }
    return null;
  }
  
  public static <T> Future<T> forkFuture(Callable<T> procedure) {
    return executor.submit(procedure);
  }

  public static <T extends Poolable> Callable<T> $claim(
          final PoolTap<T> pool, final Timeout timeout) {
    return () -> {
      try {
        return pool.claim(timeout);
      } catch (InterruptedException e) {
        throw new PoolException("Claim interrupted", e);
      }
    };
  }

  public static <T extends Poolable> Callable<T> $claimRelease(
      final PoolTap<T> pool, final Timeout timeout) {
    return () -> {
      try {
        pool.claim(timeout).release();
        return null;
      } catch (InterruptedException e) {
        throw new PoolException("Claim interrupted", e);
      }
    };
  }
  
  public static <T> Callable<T> capture(
      final Callable<T> callable,
      final AtomicReference<T> ref) {
    return () -> {
      T obj = callable.call();
      ref.set(obj);
      return obj;
    };
  }
  
  public static Callable<Completion> $await(
      final Completion completion,
      final Timeout timeout) {
    return () -> {
      completion.await(timeout);
      return completion;
    };
  }
  
  public static Callable<AtomicBoolean> $await(
      final Completion completion,
      final Timeout timeout,
      final AtomicBoolean result) {
    return () -> {
      result.set(completion.await(timeout));
      return result;
    };
  }
  
  public static <T> Callable<T> $catchFrom(
      final Callable<T> procedure, final AtomicReference<Object> caught) {
    return () -> {
      try {
        return procedure.call();
      } catch (Exception e) {
        caught.set(e);
      }
      return null;
    };
  }
  
  public static Callable<Void> $interruptUponState(
      final Thread thread, final Thread.State state) {
    return () -> {
      waitForThreadState(thread, state);
      thread.interrupt();
      return null;
    };
  }
  
  public static Callable<Void> $delayedReleases(
      long delay, TimeUnit delayUnit, Poolable... objs) {
    return () -> {
      List<Poolable> list = new ArrayList<>(Arrays.asList(objs));
      try {
        while (!list.isEmpty()) {
          delayUnit.sleep(delay);
          list.removeFirst().release();
        }
      } catch (InterruptedException e) {
        for (Poolable obj : list) {
          obj.release();
        }
      }
      return null;
    };
  }
  
  public static void waitForThreadState(Thread thread, Thread.State targetState) {
    AtomicReference<Throwable> capture = capture(thread);
    long start = TimeUnit.NANOSECONDS.toMillis(System.nanoTime());
    long check = start + 30;
    State currentState = thread.getState();
    while (currentState != targetState) {
      try {
        assertThat(currentState).isNotEqualTo(State.TERMINATED);
      } catch (Throwable e) {
        Throwable suppressed;
        if (capture != null && (suppressed = capture.get()) != null) {
          e.addSuppressed(suppressed);
        }
        throw e;
      }
      long now = TimeUnit.NANOSECONDS.toMillis(System.nanoTime());
      if (now > check) {
        long elapsed = now - start;
        System.err.println("Warning: Been waiting to observe thread state " +
            targetState + " for " + elapsed + " ms. Current observation: " +
            currentState);
        check = now + 30;
      }
      Thread.yield();
      currentState = thread.getState();
    }
  }
  
  public static void join(Thread thread) throws ExecutionException {
    try {
      thread.join();
      Thread.UncaughtExceptionHandler handler =
          thread.getUncaughtExceptionHandler();
      if (handler instanceof CatchingExceptionHandler catchingHandler) {
        Throwable th = catchingHandler.get();
        if (th != null) {
          throw new ExecutionException(th);
        }
      }
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
      throw new AssertionError("Unexpected exception", e);
    }
  }

  public static void spinwait(long ms) {
    long now = System.nanoTime();
    long deadline = now + TimeUnit.MILLISECONDS.toNanos(ms);
    while (now < deadline) {
      now = System.nanoTime();
    }
  }

  public static void claimRelease(
      int count,
      Pool<? extends Poolable> pool,
      Timeout timeout) throws InterruptedException {
    Poolable[] objs = new Poolable[count];
    for (int i = 0; i < count; i++) {
      objs[i] = pool.claim(timeout);
    }
    for (int i = 0; i < count; i++) {
      objs[i].release();
    }
  }

  public static void sneakyThrow(Throwable throwable) {
    UnitKit._sneakyThrow(throwable);
  }

  /**
   * http://youtu.be/7qXXWHfJha4
   */
  @SuppressWarnings("unchecked")
  private static <T extends Throwable> void _sneakyThrow(
      Throwable throwable) throws T {
    throw (T) throwable;
  }
}
