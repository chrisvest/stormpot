/*
 * Copyright (C) 2011-2014 Chris Vest (mr.chrisvest@gmail.com)
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *         http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package stormpot.slow;

import org.junit.rules.TestRule;
import org.junit.runner.Description;
import org.junit.runners.model.Statement;

import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;

import static org.hamcrest.Matchers.is;
import static org.junit.Assert.assertThat;

class ExecutorTestRule implements TestRule {
  private ExecutorService executor;
  private List<Future<?>> futuresToPrintOnFailure = new ArrayList<>();

  ExecutorService getExecutorService() {
    return executor;
  }

  @Override
  public Statement apply(final Statement base, final Description description) {
    return new Statement() {
      @Override
      public void evaluate() throws Throwable {
        String testName = description.getDisplayName();
        TestThreadFactory threadFactory = new TestThreadFactory(testName);
        executor = createExecutor(threadFactory);
        try {
          base.evaluate();
          executor.shutdown();
          if (!executor.awaitTermination(5000, TimeUnit.SECONDS)) {
            throw new Exception(
                "ExecutorService.shutdown timed out after 5 second");
          }
          threadFactory.verifyAllThreadsTerminatedSuccessfully();
        } catch (Throwable throwable) {
          threadFactory.dumpAllThreads();
          printFuturesForFailure();
          throw throwable;
        }
      }
    };
  }

  private ExecutorService createExecutor(ThreadFactory threadFactory) {
    return Executors.newCachedThreadPool(threadFactory);
  }

  void printOnFailure(List<Future<?>> futures) {
    futuresToPrintOnFailure.addAll(futures);
  }

  void printOnFailure(Future<?> future) {
    futuresToPrintOnFailure.add(future);
  }

  private void printFuturesForFailure() {
    System.err.println(
        "\n===[ Dumping all registered futures ]===\n");
    for (Future<?> future : futuresToPrintOnFailure) {
      System.err.printf(
          "future = %s, isDone? %s, isCancelled? %s%n",
          future,
          future.isDone(),
          future.isCancelled());
      if (future.isDone()) {
        System.err.print("    result: ");
        try {
          System.err.println(future.get());
        } catch (Exception e) {
          e.printStackTrace();
        }
      }
    }
    System.err.println(
        "\n===[ End dumping all registered futures ]===\n");
  }

  private class TestThreadFactory implements ThreadFactory {
    private final String testName;
    private final AtomicInteger threadCounter = new AtomicInteger();
    private final List<Thread> threads =
        Collections.synchronizedList(new ArrayList<>());

    private TestThreadFactory(String testName) {
      this.testName = testName;
    }

    @Override
    public Thread newThread(Runnable runnable) {
      int id = threadCounter.incrementAndGet();
      Thread thread = new Thread(runnable, "TestThread#" + id + "[" + testName + "]");
      threads.add(thread);
      return thread;
    }

    void verifyAllThreadsTerminatedSuccessfully() {
      synchronized (threads) {
        for (Thread thread : threads) {
          // The Thread.State is updated asynchronously by the JVM,
          // so we occasionally have to do a couple of retries before we
          // observe the state change.
          Thread.State state = thread.getState();
          int tries = 100;
          while (state != Thread.State.TERMINATED && tries --> 0) {
            try {
              thread.join(10);
            } catch (InterruptedException e) {
              throw new AssertionError(e);
            }
            state = thread.getState();
          }
          if (tries == 0) {
            // Okay, this is odd. Let's ask everybody to come to a safe-point
            // before we pass our final judgement on the thread state.
            System.gc();
            state = thread.getState();
          }
          assertThat(
              "Unexpected thread state: " + thread + " (id " + thread.getId() + ")",
              state,
              is(Thread.State.TERMINATED));
        }
      }
    }

    void dumpAllThreads() throws Exception {
      synchronized (threads) {
        System.err.println(
            "\n===[ Dumping stack traces for all created threads ]===\n");
        for (Thread thread : threads) {
          StackTraceElement[] stackTrace = thread.getStackTrace();
          printStackTrace(thread, stackTrace);
        }
        System.err.println(
            "\n===[ End stack traces for all created threads ]===\n");

        System.err.println(
            "\n===[ Dumping stack traces for all other threads ]===\n");
        Set<Map.Entry<Thread, StackTraceElement[]>> entries =
            Thread.getAllStackTraces().entrySet();
        for (Map.Entry<Thread,StackTraceElement[]> entry : entries) {
          printStackTrace(entry.getKey(), entry.getValue());
        }
        System.err.println(
            "\n===[ End stack traces for all other threads ]===\n");
      }
    }

    private void printStackTrace(
        Thread thread,
        StackTraceElement[] stackTrace) {
      Exception printer = new Exception(
          "Stack trace for " + thread + " (id " + thread.getId() +
              "), state = " + thread.getState());
      printer.setStackTrace(stackTrace);
      printer.printStackTrace();
    }
  }
}
