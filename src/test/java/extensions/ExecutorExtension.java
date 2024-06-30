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
package extensions;

import org.junit.jupiter.api.extension.AfterEachCallback;
import org.junit.jupiter.api.extension.BeforeEachCallback;
import org.junit.jupiter.api.extension.Extension;
import org.junit.jupiter.api.extension.ExtensionContext;

import java.lang.management.LockInfo;
import java.lang.management.ManagementFactory;
import java.lang.management.MonitorInfo;
import java.lang.management.ThreadInfo;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.StringJoiner;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

public class ExecutorExtension implements Extension, BeforeEachCallback, AfterEachCallback {
  private TestThreadFactory threadFactory;
  private ExecutorService executor;
  private final List<Future<?>> futuresToPrintOnFailure = new ArrayList<>();

  public ExecutorService getExecutorService() {
    return executor;
  }

  @Override
  public void beforeEach(ExtensionContext context) {
    String displayName = context.getDisplayName();
    threadFactory = new TestThreadFactory(displayName);
    executor = createExecutor(threadFactory);
  }

  @Override
  public void afterEach(ExtensionContext context) throws Exception {
    try {
      executor.shutdown();
      if (!executor.awaitTermination(5000, TimeUnit.SECONDS)) {
        throw new Exception("ExecutorService.shutdown timed out after 5 second");
      }
      threadFactory.verifyAllThreadsTerminatedSuccessfully();
      if (context.getExecutionException().isPresent()) {
        handleFailure();
      }
    } catch (Throwable throwable) {
      handleFailure();
      throw throwable;
    }
  }

  private void handleFailure() {
    threadFactory.dumpAllThreads();
    printFuturesForFailure();
  }

  private ExecutorService createExecutor(ThreadFactory threadFactory) {
    return Executors.newCachedThreadPool(threadFactory);
  }

  public void printOnFailure(List<Future<?>> futures) {
    futuresToPrintOnFailure.addAll(futures);
  }

  public void printOnFailure(Future<?> future) {
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

  private static class TestThreadFactory implements ThreadFactory {
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
          while (state != Thread.State.TERMINATED && tries-- > 0) {
            try {
              thread.join(100);
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
          StackTraceElement[] stackTrace = thread.getStackTrace();
          StringJoiner joiner = new StringJoiner("\n\t");
          joiner.setEmptyValue("\tno stack trace");
          for (StackTraceElement frame : stackTrace) {
            joiner.add(frame.toString());
          }
          assertThat(state).as("Unexpected thread state: " + thread +
              " (id " + thread.threadId() + ") stack trace: \n" + joiner)
              .isEqualTo(Thread.State.TERMINATED);
        }
      }
    }

    void dumpAllThreads() {
      ThreadInfo[] threadInfos = ManagementFactory.getThreadMXBean().dumpAllThreads(true, true);
      Map<Long, ThreadInfo> infos = new HashMap<>();
      for (ThreadInfo threadInfo : threadInfos) {
        infos.put(threadInfo.getThreadId(), threadInfo);
      }
      synchronized (threads) {
        System.err.println(
            "\n===[ Dumping stack traces for all created threads ]===\n");
        Set<Long> createdThreads = new HashSet<>();
        for (Thread thread : threads) {
          createdThreads.add(thread.threadId());
          StackTraceElement[] stackTrace = thread.getStackTrace();
          printStackTrace(thread, infos.get(thread.threadId()), stackTrace);
        }
        System.err.println(
            "\n===[ End stack traces for all created threads ]===\n");

        System.err.println(
            "\n===[ Dumping stack traces for all other threads ]===\n");
        Set<Map.Entry<Thread, StackTraceElement[]>> entries =
            Thread.getAllStackTraces().entrySet();
        for (Map.Entry<Thread,StackTraceElement[]> entry : entries) {
          Thread thread = entry.getKey();
          if (!createdThreads.contains(thread.threadId())) {
            printStackTrace(thread, infos.get(thread.threadId()), entry.getValue());
          }
        }
        System.err.println(
            "\n===[ End stack traces for all other threads ]===\n");
      }
    }

    private void printStackTrace(
            Thread thread,
            ThreadInfo info,
            StackTraceElement[] stackTrace) {
      Thread.State state = thread.getState();
      LockInfo lockInfo = info.getLockInfo();
      MonitorInfo[] monitors = info.getLockedMonitors();
      Map<StackTraceElement, MonitorInfo> locks = new HashMap<>();
      for (MonitorInfo monitor : monitors) {
        locks.put(monitor.getLockedStackFrame(), monitor);
      }
      System.err.printf("\"%s\" #%s prio=%s daemon=%s tid=0 nid=0 %s%n   java.lang.Thread.State: %s%n",
              thread.getName(), thread.threadId(), thread.getPriority(), thread.isDaemon(),
              state.toString().toLowerCase(), state);
      for (StackTraceElement ste : stackTrace) {
        System.err.printf("\tat %s%n", ste);
        if (lockInfo != null) {
          System.err.printf("\t- waiting on %s%n", lockInfo);
          lockInfo = null;
        }
        MonitorInfo monitor = locks.get(ste);
        if (monitor != null) {
          System.err.printf("\t- locked %s%n", monitor);
        }
      }
      System.err.println();
    }
  }
}
