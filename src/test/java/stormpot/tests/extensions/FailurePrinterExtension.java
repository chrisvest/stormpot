/*
 * Copyright © 2011 Chris Vest (mr.chrisvest@gmail.com)
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
package stormpot.tests.extensions;


import org.junit.jupiter.api.extension.AfterEachCallback;
import org.junit.jupiter.api.extension.BeforeEachCallback;
import org.junit.jupiter.api.extension.Extension;
import org.junit.jupiter.api.extension.ExtensionContext;

import java.lang.management.CompilationMXBean;
import java.lang.management.GarbageCollectorMXBean;
import java.lang.management.ManagementFactory;
import java.lang.management.OperatingSystemMXBean;
import java.lang.management.ThreadMXBean;
import java.util.List;
import java.util.Optional;

/**
 * An Extension that ensures that any failing tests have their stack-trace
 * printed to stderr.
 * <p>
 * This is useful for when the tests are running on a build-server, and you'd
 * like the details of any failure to be printed to the build-log. A nice thing
 * if the build system does not make the build artifacts with the test failures
 * available.
 * 
 * @author Chris Vest
 */
public class FailurePrinterExtension implements Extension, BeforeEachCallback, AfterEachCallback {
  private ThreadMXBean threadMXBean;
  private CompilationMXBean compilationMXBean;
  private List<GarbageCollectorMXBean> garbageCollectorMXBeans;
  private OperatingSystemMXBean operatingSystemMXBean;
  private long startCpuTimeNs;
  private long startUserTimeNs;
  private int startThreadCount;
  private long startCompilationTimeMs;
  private long startCollectionTimeMs;
  private double loadAverage;
  private int cores;

  @Override
  public void beforeEach(ExtensionContext context) {
    threadMXBean = ManagementFactory.getThreadMXBean();
    compilationMXBean = ManagementFactory.getCompilationMXBean();
    garbageCollectorMXBeans = ManagementFactory.getGarbageCollectorMXBeans();
    operatingSystemMXBean = ManagementFactory.getOperatingSystemMXBean();
    if (threadMXBean.isCurrentThreadCpuTimeSupported()) {
      threadMXBean.setThreadCpuTimeEnabled(true);
    }

    startCpuTimeNs = getCpuTimeNs(threadMXBean);
    startUserTimeNs = getUserTimeNs(threadMXBean);
    startThreadCount = threadMXBean.getThreadCount();
    startCompilationTimeMs = getCompilationTimeMs(compilationMXBean);
    startCollectionTimeMs = collectionTimeMs(garbageCollectorMXBeans);
    loadAverage = operatingSystemMXBean.getSystemLoadAverage();

    cores = operatingSystemMXBean.getAvailableProcessors();
  }

  @SuppressWarnings("CallToPrintStackTrace")
  @Override
  public void afterEach(ExtensionContext context) {
    Optional<Throwable> executionException = context.getExecutionException();
    if (executionException.isPresent()) {
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
      executionException.get().printStackTrace();
      System.err.flush();
    }
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
    return compilationMXBean.isCompilationTimeMonitoringSupported() ?
        compilationMXBean.getTotalCompilationTime() : 0;
  }

  private long getUserTimeNs(ThreadMXBean threadMXBean) {
    return threadMXBean.isCurrentThreadCpuTimeSupported() ?
        threadMXBean.getCurrentThreadUserTime() : 0;
  }

  private long getCpuTimeNs(ThreadMXBean threadMXBean) {
    return threadMXBean.isCurrentThreadCpuTimeSupported() ?
        threadMXBean.getCurrentThreadCpuTime() : 0;
  }
}
