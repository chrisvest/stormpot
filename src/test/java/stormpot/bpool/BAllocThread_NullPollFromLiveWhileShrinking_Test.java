/*
 * Copyright 2012 Chris Vest
 * 
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 * 
 *      http://www.apache.org/licenses/LICENSE-2.0
 * 
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package stormpot.bpool;

import org.junit.Test;
import stormpot.AllocThread_NullPollFromLiveWhileShrinking_TestTemplate;
import stormpot.Callable;
import stormpot.Config;
import stormpot.Poolable;

import java.util.LinkedList;
import java.util.Queue;
import java.util.concurrent.BlockingQueue;

public class BAllocThread_NullPollFromLiveWhileShrinking_Test
extends AllocThread_NullPollFromLiveWhileShrinking_TestTemplate<BSlot<Poolable>, BAllocThread<Poolable>> {

  protected BAllocThread<Poolable> createAllocThread(
      BlockingQueue<BSlot<Poolable>> live, BlockingQueue<BSlot<Poolable>> dead,
      Config<Poolable> config) {
    return new BAllocThread<Poolable>(live, dead, config, new BSlot<Poolable>(live));
  }

  protected void setTargetSize(
      final BAllocThread<Poolable> thread,
      final int size) {
    thread.setTargetSize(size);
  }

  @Override
  protected BSlot<Poolable> createSlot(BlockingQueue<BSlot<Poolable>> live) {
    return new BSlot<Poolable>(live);
  }
  
  @Test(timeout = 300) public void
  pollingClaimedSlotsMustBeSentBackToTheLiveQueue() {
    Queue<Callable<BSlot<Poolable>>> liveCalls = new LinkedList<Callable<BSlot<Poolable>>>();
    Queue<Callable<BSlot<Poolable>>> deadCalls = new LinkedList<Callable<BSlot<Poolable>>>();
    BlockingQueue<BSlot<Poolable>> live = callQueue(liveCalls);
    BlockingQueue<BSlot<Poolable>> dead = callQueue(deadCalls);
    Config<Poolable> config = createConfig();
    BAllocThread<Poolable> th = createAllocThread(live, dead, config);

    deadCalls.offer(ret(null));
    deadCalls.offer(ret(null));
    deadCalls.offer(setSizeReturn(th, 1, null));
    BSlot<Poolable> slot = createSlot(live);
    slot.dead2live();
    slot.live2claim();
    liveCalls.offer(ret(slot));
    liveCalls.offer(throwStop());
    
    try {
      th.run();
    } catch (Stop _) {
      // we're happy now
    }
  }
}
