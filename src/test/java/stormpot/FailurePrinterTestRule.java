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
package stormpot;

import org.junit.internal.AssumptionViolatedException;
import org.junit.rules.TestRule;
import org.junit.runner.Description;
import org.junit.runners.model.Statement;

import java.lang.management.*;
import java.util.List;

/**
 * A TestRule that ensures that any failing tests have their stack-trace
 * printed to stderr.
 * 
 * This is useful for when the tests are running on a build-server, and you'd
 * like the details of any failure to be printed to the build-log. A nice thing
 * if the build system does not make the build artifacts with the test failures
 * available.
 * 
 * @author cvh
 */
public class FailurePrinterTestRule implements TestRule {
  @Override
  public Statement apply(final Statement base, final Description description) {
    return new Statement() {
      @Override
      public void evaluate() throws Throwable {
        ThreadMXBean threadMXBean = ManagementFactory.getThreadMXBean();
        CompilationMXBean compilationMXBean = ManagementFactory.getCompilationMXBean();
        List<GarbageCollectorMXBean> garbageCollectorMXBeans = ManagementFactory.getGarbageCollectorMXBeans();
        OperatingSystemMXBean operatingSystemMXBean = ManagementFactory.getOperatingSystemMXBean();
        if (threadMXBean.isCurrentThreadCpuTimeSupported())
        {
          threadMXBean.setThreadCpuTimeEnabled(true);
        }

        long startCpuTimeNs = getCpuTimeNs(threadMXBean);
        long startUserTimeNs = getUserTimeNs(threadMXBean);
        int startThreadCount = threadMXBean.getThreadCount();
        long startCompilationTimeMs = getCompilationTimeMs(compilationMXBean);
        long startCollectionTimeMs = collectionTimeMs(garbageCollectorMXBeans);
        double loadAverage = operatingSystemMXBean.getSystemLoadAverage();

        int cores = operatingSystemMXBean.getAvailableProcessors();

        try {
          base.evaluate();
        } catch (AssumptionViolatedException ignore) {
          // This is fine.
        } catch (Throwable th) {
          long cpuTimeNs = getCpuTimeNs(threadMXBean) - startCpuTimeNs;
          long userTimeNs = getUserTimeNs(threadMXBean) - startUserTimeNs;
          int threadCount = threadMXBean.getThreadCount();
          long compilationTimeMs = getCompilationTimeMs(compilationMXBean) - startCompilationTimeMs;
          long collectionTimeMs = collectionTimeMs(garbageCollectorMXBeans) - startCollectionTimeMs;
          double newLoadAverage = operatingSystemMXBean.getSystemLoadAverage();
          System.err.printf("Failed test:%n" +
              "\tcpu-cores = %s%n" +
              "\tcpuTimeNs = %s%n" +
              "\tuserTimeNs = %s%n" +
              "\tcompilationTimeMs = %s%n" +
              "\tcollectionTimeMs = %s%n" +
              "\tload average = %s -> %s%n" +
              "\tthreads = %s -> %s%n",
              cores,
              cpuTimeNs,
              userTimeNs,
              compilationTimeMs,
              collectionTimeMs,
              loadAverage, newLoadAverage,
              startThreadCount, threadCount);
          th.printStackTrace();
          System.err.flush();
          throw th;
        }
      }
    };
  }

  private long collectionTimeMs(List<GarbageCollectorMXBean> garbageCollectorMXBeans) {
    long sum = 0L;
    for (GarbageCollectorMXBean gc : garbageCollectorMXBeans) {
      long collectionTime = gc.getCollectionTime();
      if (collectionTime != -1) {
        sum += collectionTime;
      }
    }
    return sum;
  }

  private long getCompilationTimeMs(CompilationMXBean compilationMXBean) {
    return compilationMXBean.isCompilationTimeMonitoringSupported()?
        compilationMXBean.getTotalCompilationTime() : 0;
  }

  private long getUserTimeNs(ThreadMXBean threadMXBean) {
    return threadMXBean.isCurrentThreadCpuTimeSupported()?
        threadMXBean.getCurrentThreadUserTime() : 0;
  }

  private long getCpuTimeNs(ThreadMXBean threadMXBean) {
    return threadMXBean.isCurrentThreadCpuTimeSupported()?
        threadMXBean.getCurrentThreadCpuTime() : 0;
  }
}
