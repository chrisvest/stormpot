/*
 * Copyright © 2011-2019 Chris Vest (mr.chrisvest@gmail.com)
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
package examples;

import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.binder.MeterBinder;
import stormpot.ManagedPool;
import stormpot.Pool;

public class MicrometerBinderExample implements MeterBinder {
  private static final String SUCCESSFUL_ALLOCATIONS = "allocationCount";
  private static final String FAILED_ALLOCATIONS = "failedAllocationCount";
  private static final String LEAKED_OBJECTS = "leakedObjectCount";
  private static final String SEP = ".";
  private final ManagedPool pool;
  private final String poolName;

  public MicrometerBinderExample(String poolName, Pool<?> pool) {
    this.poolName = poolName;
    this.pool = pool.getManagedPool();
  }

  @Override
  public void bindTo(MeterRegistry registry) {
    Gauge.builder(poolName + SEP + SUCCESSFUL_ALLOCATIONS, pool::getAllocationCount)
        .description("Allocation count for pool")
        .baseUnit(SUCCESSFUL_ALLOCATIONS)
        .register(registry);

    Gauge.builder(poolName + SEP + FAILED_ALLOCATIONS, pool::getFailedAllocationCount)
        .description("Failed allocation count for pool")
        .baseUnit(FAILED_ALLOCATIONS)
        .register(registry);

    Gauge.builder(poolName + SEP + LEAKED_OBJECTS, pool::getLeakedObjectsCount)
        .description("Leaked object count for pool")
        .baseUnit(LEAKED_OBJECTS)
        .register(registry);
  }
}
