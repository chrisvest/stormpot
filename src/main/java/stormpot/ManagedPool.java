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

import javax.management.MXBean;

/**
 * This is the JMX management interface for Stormpot object pools.
 * <p>
 * Using this interface, pools can be exposed to external management as an
 * MXBean. Since its an MXBean, and not just an MBean, it imposes no
 * requirement that external parties knows about the Stormpot types.
 * <p>
 * Once you have created you pool, it is easy to expose it through the platform
 * MBeanServer, or any MBeanServer you like:
 *
 * <pre><code>
 *   BlazePool&lt;MyPoolable&gt; pool = new BlazePool&lt;MyPoolable&gt;(...);
 *   MBeanServer server = ManagementFactory.getPlatformMBeanServer();
 *   ObjectName name = new ObjectName("com.myapp:objectpool=stormpot");
 *   server.registerMBean(pool, name);
 * </code></pre>
 *
 * Using the platform MBeanServer will make the pool visible to tools like
 * JConsole and VisualVM.
 */
@MXBean
public interface ManagedPool {
  /**
   * Return the number of objects the pool has allocated since it was created.
   */
  long getAllocationCount();

  /**
   * Return the number of allocations that has failed, either because the
   * allocator threw an exception or because it returned null, since the pool
   * was created.
   */
  long getFailedAllocationCount();

  // The *TargetSize methods are duplicated here, because we would otherwise
  // have to provide a type parameter for the ResizablePool interface, if we
  // were to extend it.

  /**
   * @see ResizablePool#setTargetSize(int)
   */
  void setTargetSize(int size);

  /**
   * @see ResizablePool#getTargetSize()
   */
  int getTargetSize();

  /**
   * Returns 'true' if the shut down process has been started on this pool,
   * 'false' otherwise. This method does not reveal whether or not the shut
   * down process has completed.
   */
  boolean isShutDown();

  double getObjectLifetimePercentile(double percentile);

  double getAllocationLatencyPercentile(double percentile);

  double getAllocationFailureLatencyPercentile(double percentile);

  double getReallocationLatencyPercentile(double percentile);

  double getReallocationFailureLatencyPercentile(double percentile);

  double getDeallocationLatencyPercentile(double percentile);

  long getLeakedObjectsCount();
}
