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

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Semaphore;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.locks.Lock;

import static java.util.Collections.synchronizedList;

public class AlloKit {
  public interface Action {
    GenericPoolable apply(Slot slot, GenericPoolable obj) throws Exception;
  }

  public interface CountingAllocator extends Allocator<GenericPoolable> {
    int countAllocations();
    int countDeallocations();
    List<GenericPoolable> getAllocations();
    List<GenericPoolable> getDeallocations();
    void clearLists();
  }

  public interface CountingReallocator
      extends CountingAllocator, Reallocator<GenericPoolable> {
    int countReallocations();
    List<GenericPoolable> getReallocations();
  }

  static class ActionSeq {
    private final Action[] actions;
    private int counter;

    ActionSeq(Action... actions) {
      this.actions = actions;
      counter = 0;
    }

    GenericPoolable applyNext(Slot slot, GenericPoolable obj) throws Exception {
      Action action = actions[counter];
      counter = Math.min(actions.length - 1, counter + 1);
      return action.apply(slot, obj);
    }
  }

  public static class OnAllocation extends ActionSeq {
    OnAllocation(Action... actions) {
      super(actions);
    }
  }

  public static class OnReallocation extends ActionSeq {
    OnReallocation(Action... actions) {
      super(actions);
    }
  }

  public static class OnDeallocation extends ActionSeq {
    OnDeallocation(Action... actions) {
      super(actions);
    }
  }

  private static class CountingAllocatorImpl implements CountingAllocator {
    private final OnAllocation onAllocation;
    private final OnDeallocation onDeallocation;
    private final List<GenericPoolable> allocations;
    private final List<GenericPoolable> deallocations;

    private CountingAllocatorImpl(
        OnAllocation onAllocation,
        OnDeallocation onDeallocation) {
      this.onAllocation = onAllocation;
      this.onDeallocation = onDeallocation;
      allocations = synchronizedList(new ArrayList<>());
      deallocations = synchronizedList(new ArrayList<>());
    }

    @Override
    public int countAllocations() {
      return allocations.size();
    }

    @Override
    public int countDeallocations() {
      return deallocations.size();
    }

    @Override
    public List<GenericPoolable> getAllocations() {
      return allocations;
    }

    @Override
    public List<GenericPoolable> getDeallocations() {
      return deallocations;
    }

    @Override
    public void clearLists() {
      allocations.clear();
      deallocations.clear();
    }

    @Override
    public GenericPoolable allocate(Slot slot) throws Exception {
      assert slot != null : "Slot cannot be null in allocate";
      GenericPoolable obj = onAllocation.applyNext(slot, null);
      allocations.add(obj);
      return obj;
    }

    @Override
    public void deallocate(GenericPoolable poolable) throws Exception {
      // no not-null assertion because exceptions from deallocate are ignored
      deallocations.add(poolable);
      onDeallocation.applyNext(null, poolable);
    }
  }

  private static class CountingReallocatorImpl
      extends CountingAllocatorImpl
      implements CountingReallocator {

    private final OnReallocation onReallocation;
    private final List<GenericPoolable> reallocations;

    private CountingReallocatorImpl(
        OnAllocation onAllocation,
        OnDeallocation onDeallocation,
        OnReallocation onReallocation) {
      super(onAllocation, onDeallocation);
      this.onReallocation = onReallocation;
      reallocations = synchronizedList(new ArrayList<>());
    }

    @Override
    public int countReallocations() {
      return reallocations.size();
    }

    @Override
    public List<GenericPoolable> getReallocations() {
      return reallocations;
    }

    @Override
    public void clearLists() {
      super.clearLists();
      reallocations.clear();
    }

    @Override
    public GenericPoolable reallocate(
        Slot slot, GenericPoolable poolable) throws Exception {
      assert slot != null : "Slot cannot be null in reallocate";
      assert poolable != null : "Cannot reallocate null Poolable for slot: " + slot;
      GenericPoolable obj = onReallocation.applyNext(slot, poolable);
      reallocations.add(obj);
      return obj;
    }
  }

  public static OnAllocation alloc(Action... actions) {
    return new OnAllocation(actions);
  }

  public static OnDeallocation dealloc(Action... actions) {
    return new OnDeallocation(actions);
  }

  public static OnReallocation realloc(Action... actions) {
    return new OnReallocation(actions);
  }

  public static CountingAllocator allocator(
      OnAllocation onAllocation,
      OnDeallocation onDeallocation) {
    return new CountingAllocatorImpl(onAllocation, onDeallocation);
  }

  public static CountingAllocator allocator(OnAllocation onAllocation) {
    return allocator(onAllocation, dealloc($null));
  }

  public static CountingAllocator allocator(OnDeallocation onDeallocation) {
    return allocator(alloc($new), onDeallocation);
  }

  public static CountingAllocator allocator() {
    return allocator(alloc($new), dealloc($null));
  }

  public static CountingReallocator reallocator(
      OnAllocation onAllocation,
      OnDeallocation onDeallocation,
      OnReallocation onReallocation) {
    return new CountingReallocatorImpl(
        onAllocation, onDeallocation, onReallocation);
  }

  public static CountingReallocator reallocator(
      OnAllocation onAllocation,
      OnDeallocation onDeallocation) {
    return reallocator(onAllocation, onDeallocation, realloc($new));
  }

  public static CountingReallocator reallocator(
      OnAllocation onAllocation,
      OnReallocation onReallocation) {
    return reallocator(onAllocation, dealloc($null), onReallocation);
  }

  public static CountingReallocator reallocator(
      OnDeallocation onDeallocation,
      OnReallocation onReallocation) {
    return reallocator(alloc($new), onDeallocation, onReallocation);
  }

  public static CountingReallocator reallocator(OnAllocation onAllocation) {
    return reallocator(onAllocation, dealloc($null), realloc($new));
  }

  public static CountingReallocator reallocator(OnDeallocation onDeallocation) {
    return reallocator(alloc($new), onDeallocation, realloc($new));
  }

  public static CountingReallocator reallocator(OnReallocation onReallocation) {
    return reallocator(alloc($new), dealloc($null), onReallocation);
  }

  public static CountingReallocator reallocator() {
    return reallocator(alloc($new), dealloc($null), realloc($new));
  }

  public static final Action $new = (slot, obj) -> new GenericPoolable(slot);

  public static final Action $null = (slot, obj) -> null;

  public static Action $throw(final Exception e) {
    return (slot, obj) -> {
      throw e;
    };
  }

  public static Action $throw(final Error e) {
    return (slot, obj) -> {
      throw e;
    };
  }

  public static Action $acquire(final Semaphore semaphore, final Action action) {
    return (slot, obj) -> {
      semaphore.acquire();
      return action.apply(slot, obj);
    };
  }

  public static Action $release(final Semaphore semaphore, final Action action) {
    return (slot, obj) -> {
      semaphore.release();
      return action.apply(slot, obj);
    };
  }

  public static Action $countDown(
      final CountDownLatch latch,
      final Action action) {
    return (slot, obj) -> {
      try {
        return action.apply(slot, obj);
      } finally {
        latch.countDown();
      }
    };
  }

  public static Action $sync(final Lock lock, final Action action) {
    return (slot, obj) -> {
      lock.lock();
      try {
        return action.apply(slot, obj);
      } finally {
        lock.unlock();
      }
    };
  }

  public static Action $observeNull(
      final AtomicBoolean observedNull,
      final Action action) {
    return (slot, obj) -> {
      if (obj == null) {
        observedNull.set(true);
      }
      return action.apply(slot, obj);
    };
  }

  public static Action $sleep(final long millis, final Action action) {
    return (slot, obj) -> {
      Thread.sleep(millis);
      return action.apply(slot, obj);
    };
  }

  public static Action $if(
      final AtomicBoolean cond,
      final Action then,
      final Action alt) {
    return (slot, obj) -> {
      if (cond.get()) {
        return then.apply(slot, obj);
      }
      return alt.apply(slot, obj);
    };
  }

  public static Action $incrementAnd(
      final AtomicLong counter,
      final Action action) {
    return new Action() {
      @Override
      public GenericPoolable apply(Slot slot, GenericPoolable obj) throws Exception {
        counter.getAndIncrement();
        return action.apply(slot, obj);
      }
    };
  }
}
