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

import java.util.concurrent.BlockingQueue;

import stormpot.AllocThread_NullPollFromLiveWhileShrinking_TestTemplate;
import stormpot.Config;
import stormpot.Poolable;

public class BAllocThread_NullPollFromLiveWhileShrinking_Test
extends AllocThread_NullPollFromLiveWhileShrinking_TestTemplate<BSlot<Poolable>, BAllocThread<Poolable>> {

  protected BAllocThread<Poolable> createAllocThread(
      BlockingQueue<BSlot<Poolable>> live, BlockingQueue<BSlot<Poolable>> dead,
      Config<Poolable> config) {
    BAllocThread<Poolable> th = new BAllocThread<Poolable>(live, dead, config);
    return th;
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
}
