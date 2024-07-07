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
package stormpot.internal;

import stormpot.Completion;
import stormpot.Poolable;

import java.util.concurrent.LinkedTransferQueue;
import java.util.concurrent.ThreadFactory;

public class ThreadedAllocationController<T extends Poolable> extends AllocationController<T> {
  private final BAllocThread<T> allocator;
  private final Thread allocatorThread;
  ThreadedAllocationController(
      LinkedTransferQueue<BSlot<T>> live,
      RefillPile<T> disregardPile,
      RefillPile<T> newAllocations,
      PoolBuilderImpl<T> builder,
      BSlot<T> poisonPill) {
    allocator = new BAllocThread<>(live, disregardPile, newAllocations, builder, poisonPill);
    ThreadFactory factory = builder.getThreadFactory();
    allocatorThread = factory.newThread(allocator);
    allocatorThread.start();
  }

  @Override
  public Completion shutdown() {
    return allocator.shutdown(allocatorThread);
  }

  @Override
  public void offerDeadSlot(BSlot<T> slot) {
    allocator.offerDeadSlot(slot);
  }

  @Override
  public void setTargetSize(int size) {
    allocator.setTargetSize(size);
  }

  @Override
  public int getTargetSize() {
    return allocator.getTargetSize();
  }

  @Override
  public long getAllocationCount() {
    return allocator.getAllocationCount();
  }

  @Override
  public long getFailedAllocationCount() {
    return allocator.getFailedAllocationCount();
  }

  @Override
  public long countLeakedObjects() {
    return allocator.countLeakedObjects();
  }
  
  @Override
  public int allocatedSize() {
    return allocator.allocatedSize();
  }
  
  @Override
  public int inUse() {
    return allocator.inUse();
  }
}
