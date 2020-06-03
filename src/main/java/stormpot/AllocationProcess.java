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
package stormpot;

import java.util.concurrent.LinkedTransferQueue;

import static stormpot.AllocationProcessMode.*;

abstract class AllocationProcess {
  public static AllocationProcess threaded() {
    return new AllocationProcess(THREADED) {
      @Override
      <T extends Poolable> AllocationController<T> buildAllocationController(
          LinkedTransferQueue<BSlot<T>> live,
          RefillPile<T> disregardPile,
          RefillPile<T> newAllocations,
          PoolBuilder<T> builder,
          BSlot<T> poisonPill) {
        return new ThreadedAllocationController<>(
            live, disregardPile, newAllocations, builder, poisonPill);
      }
    };
  }

  static AllocationProcess inline() {
    return new AllocationProcess(INLINE) {
      @Override
      <T extends Poolable> AllocationController<T> buildAllocationController(
          LinkedTransferQueue<BSlot<T>> live,
          RefillPile<T> disregardPile,
          RefillPile<T> newAllocations,
          PoolBuilder<T> builder,
          BSlot<T> poisonPill) {
        return new InlineAllocationController<>(
            live, disregardPile, newAllocations, builder, poisonPill);
      }
    };
  }

  static AllocationProcess direct() {
    return new AllocationProcess(DIRECT) {
      @Override
      <T extends Poolable> AllocationController<T> buildAllocationController(
          LinkedTransferQueue<BSlot<T>> live,
          RefillPile<T> disregardPile,
          RefillPile<T> newAllocations,
          PoolBuilder<T> builder,
          BSlot<T> poisonPill) {
        return new DirectAllocationController<>(
            live, disregardPile, builder, poisonPill);
      }
    };
  }

  private final AllocationProcessMode mode;

  protected AllocationProcess(AllocationProcessMode mode) {
    this.mode = mode;
  }

  AllocationProcessMode getMode() {
    return mode;
  }

  abstract <T extends Poolable> AllocationController<T> buildAllocationController(
      LinkedTransferQueue<BSlot<T>> live,
      RefillPile<T> disregardPile,
      RefillPile<T> newAllocations,
      PoolBuilder<T> builder,
      BSlot<T> poisonPill);
}
