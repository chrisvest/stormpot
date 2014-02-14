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
package stormpot.bpool;

import org.junit.Test;
import stormpot.AllocThread_ShutdownNullsPool_TestTemplate;
import stormpot.GenericPoolable;
import stormpot.Poolable;

import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;

import static org.hamcrest.Matchers.hasItem;
import static org.junit.Assert.assertThat;

public class BAllocThread_ShutdownNullsPoll_Test
extends AllocThread_ShutdownNullsPool_TestTemplate<BSlot<Poolable>, BAllocThread<Poolable>>{

  @Override
  protected BAllocThread<Poolable> createAllocThread(
      BlockingQueue<BSlot<Poolable>> live, BlockingQueue<BSlot<Poolable>> dead) {
    return new BAllocThread<Poolable>(live, dead, config, new BSlot<Poolable>(live));
  }

  @Override
  protected BSlot<Poolable> createSlot(BlockingQueue<BSlot<Poolable>> live) {
    BSlot<Poolable> slot = new BSlot<Poolable>(live);
    slot.obj = new GenericPoolable(slot);
    return slot;
  }

  @Test(timeout = 300) public void
  claimedSlotsInDeadQueueMustMoveToLiveQueueInShutdown() throws InterruptedException {
    BlockingQueue<BSlot<Poolable>> live = createInterruptingBlockingQueue();
    BlockingQueue<BSlot<Poolable>> dead = new LinkedBlockingQueue<BSlot<Poolable>>();
    BSlot<Poolable> slot = createSlot(live);
    slot.dead2live();
    slot.live2claim();
    dead.add(slot);
    Thread thread = createAllocThread(live, dead);
    thread.run();
    assertThat(live, hasItem(slot));
    // must complete before test times out, and not throw NPE
  }

  @Test(timeout = 300, expected = AssertionError.class) public void
  mustThrowAssertionErrorIfAttemptingToDeallocateNonDeadSlot() throws InterruptedException {
    config.setSize(0);
    BlockingQueue<BSlot<Poolable>> live = createInterruptingBlockingQueue();
    BlockingQueue<BSlot<Poolable>> dead = new LinkedBlockingQueue<BSlot<Poolable>>();
    BSlot<Poolable> slot = createSlot(live);
    slot.dead2live();
    slot.live2claim();
    dead.add(slot);
    Thread thread = createAllocThread(live, dead);
    thread.run();
    // must complete before test times out
  }
}
