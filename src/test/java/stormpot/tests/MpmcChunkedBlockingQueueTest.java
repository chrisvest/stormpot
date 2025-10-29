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
package stormpot.tests;

import org.junit.jupiter.api.Test;
import stormpot.internal.MpmcChunkedBlockingQueue;
import testkits.UnitKit;

import java.util.concurrent.FutureTask;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class MpmcChunkedBlockingQueueTest {

  private MpmcChunkedBlockingQueue<Integer> queue = new MpmcChunkedBlockingQueue<>();

  @Test
  void basicUsage() {
    assertNull(queue.poll());
    assertTrue(queue.isEmpty());
    assertFalse(queue.hasWaitingConsumer());
    assertEquals(0, queue.size());
    queue.offer(42);
    assertEquals(1, queue.size());
    queue.offer(43);
    assertEquals(2, queue.size());
    assertEquals(42, queue.poll());
    assertEquals(1, queue.size());
    assertEquals(43, queue.poll());
    assertEquals(0, queue.size());
    assertNull(queue.poll());
    assertTrue(queue.isEmpty());
  }

  @Test
  void unfulfilledBlockingWithTimeout() throws Exception {
    assertNull(queue.poll(1, TimeUnit.MILLISECONDS));
    assertFalse(queue.hasWaitingConsumer());
    queue.offer(42);
    assertFalse(queue.hasWaitingConsumer());
    assertEquals(42, queue.poll(1, TimeUnit.MILLISECONDS));
    assertFalse(queue.hasWaitingConsumer());
    assertNull(queue.poll(1, TimeUnit.MILLISECONDS));
  }

  @Test
  void offerMustUnblockTimedPoll() throws Exception {
    FutureTask<Integer> task = new FutureTask<>(() -> queue.poll(1, TimeUnit.MINUTES));
    Thread thread = Thread.ofPlatform().start(task);
    UnitKit.waitForThreadState(thread, Thread.State.TIMED_WAITING);
    queue.offer(42);
    assertEquals(42, task.get());
  }
}