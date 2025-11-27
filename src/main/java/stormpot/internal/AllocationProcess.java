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
package stormpot.internal;

import stormpot.Poolable;

import java.util.concurrent.LinkedTransferQueue;

import static stormpot.internal.AllocationProcessMode.DIRECT;
import static stormpot.internal.AllocationProcessMode.INLINE;
import static stormpot.internal.AllocationProcessMode.THREADED;

/**
 * The allocation process represent the means of building pool-specific {@link AllocationController} instances.
 */
public abstract class AllocationProcess {
  /**
   * Create an allocation process that create pool controllers that use dedicated background threads.
   * @return The new allocation process.
   */
  public static AllocationProcess threaded() {
    return new AllocationProcess(THREADED) {
      @Override
      <T extends Poolable> AllocationController<T> buildAllocationController(
          LinkedTransferQueue<BSlot<T>> live,
          RefillPile<T> disregardPile,
          RefillPile<T> newAllocations,
          PoolBuilderImpl<T> builder,
          BSlot<T> poisonPill) {
        return new ThreadedAllocationController<>(
            live, disregardPile, newAllocations, builder, poisonPill);
      }
    };
  }

  /**
   * Create an allocation process where all allocation and deallocation is performed in-line with the claim calls.
   * @return The new allocation process.
   */
  public static AllocationProcess inline() {
    return new AllocationProcess(INLINE) {
      @Override
      <T extends Poolable> AllocationController<T> buildAllocationController(
          LinkedTransferQueue<BSlot<T>> live,
          RefillPile<T> disregardPile,
          RefillPile<T> newAllocations,
          PoolBuilderImpl<T> builder,
          BSlot<T> poisonPill) {
        return new InlineAllocationController<>(
            live, disregardPile, newAllocations, builder, poisonPill);
      }
    };
  }

  /**
   * Create an allocation process where all the pooled objects are given up-front.
   * @return The new allocation process.
   */
  public static AllocationProcess direct() {
    return new AllocationProcess(DIRECT) {
      @Override
      <T extends Poolable> AllocationController<T> buildAllocationController(
          LinkedTransferQueue<BSlot<T>> live,
          RefillPile<T> disregardPile,
          RefillPile<T> newAllocations,
          PoolBuilderImpl<T> builder,
          BSlot<T> poisonPill) {
        return new DirectAllocationController<>(
            live, disregardPile, builder, poisonPill);
      }
    };
  }

  private final AllocationProcessMode mode;

  /**
   * Create an allocation process with the given operating mode.
   * @param mode The operating mode of this allocation process.
   */
  protected AllocationProcess(AllocationProcessMode mode) {
    this.mode = mode;
  }

  /**
   * Get the operating mode of this allocation process.
   * @return The operating mode.
   */
  public AllocationProcessMode getMode() {
    return mode;
  }

  abstract <T extends Poolable> AllocationController<T> buildAllocationController(
      LinkedTransferQueue<BSlot<T>> live,
      RefillPile<T> disregardPile,
      RefillPile<T> newAllocations,
      PoolBuilderImpl<T> builder,
      BSlot<T> poisonPill);
}
