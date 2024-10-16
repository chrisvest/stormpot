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

import stormpot.Allocator;
import stormpot.Completion;
import stormpot.ManagedPool;
import stormpot.Pool;
import stormpot.Poolable;

/**
 * An AllocationController implements the actual allocation and deallocation of objects
 * in a pool.
 *
 * @param <T> The type of {@link Poolable poolables} to allocate or deallocate.
 */
public abstract class AllocationController<T extends Poolable> {
  /**
   * Default constructor.
   */
  public AllocationController() {
  }

  abstract void offerDeadSlot(BSlot<T> slot);

  /**
   * @see Pool#shutdown()
   */
  abstract Completion shutdown();

  /**
   * Switch the underlying allocator to the new replacement allocator,
   * and return a {@link Completion} that completes when all current objects
   * are replaced by the given or a newer allocator.
   *
   * @param replacementAllocator The new replacement allocator.
   * @return The completion for the switching process.
   * @throws UnsupportedOperationException If this allocation controller don't
   * support switching allocators.
   */
  abstract Completion switchAllocator(Allocator<T> replacementAllocator);

  /**
   * @see Pool#setTargetSize(long)
   */
  abstract void setTargetSize(long size);

  /**
   * @see Pool#getTargetSize()
   */
  abstract long getTargetSize();

  /**
   * @see ManagedPool#getAllocationCount()
   */
  abstract long getAllocationCount();

  /**
   * @see ManagedPool#getFailedAllocationCount()
   */
  abstract long getFailedAllocationCount();

  /**
   * @see ManagedPool#getLeakedObjectsCount()
   */
  abstract long countLeakedObjects();
  
  /**
   * @see ManagedPool#getCurrentAllocatedCount()
   */
  abstract long allocatedSize();
  
  /**
   * @see ManagedPool#getCurrentInUseCount()
   */
  abstract long inUse();
}
