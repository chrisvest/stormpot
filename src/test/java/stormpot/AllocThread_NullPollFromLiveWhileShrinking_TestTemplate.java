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

import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TestRule;

import java.util.LinkedList;
import java.util.Queue;
import java.util.concurrent.BlockingQueue;

import static stormpot.AlloKit.allocator;

/**
 * This is a rather esoteric case. It turned out that when a queue-based Pool
 * was resized to become smaller, and the pool was already depleted, then
 * it was possible for the *AllocThread to pull a null from the live queue,
 * and try to deallocate it. This is obviously not good, because it would
 * kill the allocation thread, halting all meaningful function of the pool.
 * 
 * Writing a test for this, as you can probably guess, was not easy.
 * @author Chris Vest &lt;mr.chrisvest@gmail.com&gt;
 */
public abstract class AllocThread_NullPollFromLiveWhileShrinking_TestTemplate<
  SLOT,
  ALLOC_THREAD extends Thread> {
  @Rule public final TestRule failurePrinter = new FailurePrinterTestRule();
  
  @SuppressWarnings("serial")
  protected static class Stop extends RuntimeException {}

  protected BlockingQueue<SLOT> callQueue(
      final Queue<Callable<SLOT>> calls) {
    return new PrimedBlockingQueue<SLOT>(calls);
  }

  protected Callable<SLOT> ret(final SLOT slot) {
    return new Callable<SLOT>() {
      public SLOT call() {
        return slot;
      }
    };
  }

  protected Callable<SLOT> setSizeReturn(
      final ALLOC_THREAD th,
      final int size,
      final SLOT slot) {
    return new Callable<SLOT>() {
      public SLOT call() {
        setTargetSize(th, size);
        return slot;
      }
    };
  }

  protected Callable<SLOT> throwStop() {
    return new Callable<SLOT>() {
      public SLOT call() {
        throw new Stop();
      }
    };
  }

  protected Config<Poolable> createConfig() {
    Config<Poolable> config = new Config<Poolable>();
    config.setAllocator(allocator());
    config.setSize(2);
    return config;
  }
  
  @Test(timeout = 300) public void
  mustNotDeallocateNullWhenSizeIsReducedAndPoolIsDepleted() {
    Queue<Callable<SLOT>> calls = new LinkedList<Callable<SLOT>>();
    BlockingQueue<SLOT> live = callQueue(new LinkedList<Callable<SLOT>>());
    BlockingQueue<SLOT> dead = callQueue(calls);
    Config<Poolable> config = createConfig();
    ALLOC_THREAD th = createAllocThread(live, dead, config);
    
    calls.offer(ret(createSlot(live)));
    calls.offer(ret(createSlot(live)));
    calls.offer(setSizeReturn(th, 1, null));
    calls.offer(throwStop());
    
    try {
      th.run();
    } catch (Stop ignore) {
      // we're happy now
    }
  }
  
  protected abstract SLOT createSlot(BlockingQueue<SLOT> live);

  protected abstract ALLOC_THREAD createAllocThread(
      BlockingQueue<SLOT> live,
      BlockingQueue<SLOT> dead,
      Config<Poolable> config);

  protected abstract void setTargetSize(
      final ALLOC_THREAD thread,
      final int size);
}
