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

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;

import static org.hamcrest.Matchers.is;
import static org.junit.Assert.assertThat;

public class ExecutorTestRule implements TestRule {
  private TestThreadFactory threadFactory;
  private ExecutorService executor;

  public ExecutorService getExecutorService() {
    return executor;
  }

  @Override
  public Statement apply(final Statement base, final Description description) {
    return new Statement() {
      @Override
      public void evaluate() throws Throwable {
        TestThreadFactory threadFactory = new TestThreadFactory();
        executor = createExecutor(threadFactory);
        try {
          base.evaluate();
          executor.shutdown();
          if (!executor.awaitTermination(1000, TimeUnit.SECONDS)) {
            throw new Exception(
                "ExecutorService.shutdown timed out after 1 second");
          }
          threadFactory.verifyAllThreadsTerminatedSuccessfully();
        } catch (Throwable throwable) {
          threadFactory.dumpAllThreads();
          throw throwable;
        }
      }
    };
  }

  protected ExecutorService createExecutor(ThreadFactory threadFactory) {
    return Executors.newCachedThreadPool(threadFactory);
  }

  private class TestThreadFactory implements ThreadFactory {
    private final List<Thread> threads =
        Collections.synchronizedList(new ArrayList<Thread>());

    @Override
    public Thread newThread(Runnable runnable) {
      Thread thread = new Thread(runnable, "test-executor-pool-thread");
      threads.add(thread);
      return thread;
    }

    public void verifyAllThreadsTerminatedSuccessfully() {
      synchronized (threads) {
        for (Thread thread : threads) {
          assertThat(thread.getState(), is(Thread.State.TERMINATED));
        }
      }
    }

    public void dumpAllThreads() throws Exception {
      synchronized (threads) {
        System.err.println(
            "\n===[ Dumping stack traces for all created threads ]===\n");
        for (final Thread thread : threads) {
          Exception printer = new Exception(
              "Stack trace for " + thread + ", state = " + thread.getState());
          printer.setStackTrace(thread.getStackTrace());
          printer.printStackTrace();
        }
        System.err.println(
            "\n===[ End stack traces for all created threads ]===\n");
      }
    }
  }
}
