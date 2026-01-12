/*
 * Copyright © Chris Vest (mr.chrisvest@gmail.com)
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

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import stormpot.Timeout;
import stormpot.internal.StackCompletion;

import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

public class StackCompletionWithOnAwaitTest {
  private static final Timeout timeout = new Timeout(1, TimeUnit.MINUTES);

  BlockingQueue<StackCompletion.OnAwait> awaits;
  StackCompletion completion;

  @BeforeEach
  void setUp() {
    awaits = new LinkedBlockingQueue<>();
    completion = new StackCompletion(timeout -> {
      StackCompletion.OnAwait await = awaits.poll();
      if (await == null) {
        fail("No awaits expected");
      }
      return await.await(timeout);
    });
  }

  @Test
  void mustRunOnAwaitOnlyOnceWhenItCompletes() throws Exception {
    AtomicInteger counter = new AtomicInteger();
    awaits.put(timeout -> {
      counter.incrementAndGet();
      return true;
    });
    assertTrue(completion.await(timeout));
    assertTrue(completion.isCompleted());
    assertTrue(completion.await(timeout));
    assertEquals(1, counter.get());
  }

  @Test
  void mustRunOnAwaitMultipleTimesUntilItCompletes() throws Exception {
    AtomicInteger counter = new AtomicInteger();
    awaits.put(timeout -> counter.incrementAndGet() > 1);
    awaits.put(timeout -> counter.incrementAndGet() > 1);
    assertFalse(completion.await(timeout));
    assertFalse(completion.isCompleted());
    assertTrue(completion.await(timeout));
    assertTrue(completion.isCompleted());
    assertEquals(2, counter.get());
  }

  @Test
  void mustRunOnAwaitFromBlockMethod() throws Exception {
    AtomicInteger counter = new AtomicInteger();
    awaits.put(timeout -> counter.incrementAndGet() > 1);
    awaits.put(timeout -> counter.incrementAndGet() > 1);
    assertFalse(completion.block());
    assertFalse(completion.isCompleted());
    assertTrue(completion.block());
    assertTrue(completion.isCompleted());
    assertEquals(2, counter.get());
  }

  @Test
  void mustNotRunOnAwaitFromSubscriberRequestMethod() throws Exception {
    AtomicInteger awaitCounter = new AtomicInteger();
    AtomicInteger completionCounter = new AtomicInteger();
    awaits.put(timeout -> awaitCounter.incrementAndGet() > 0);
    MySubscriber subscriber = new MySubscriber(completionCounter::incrementAndGet);
    completion.subscribe(subscriber);
    assertFalse(completion.isCompleted());
    assertEquals(0, awaitCounter.get());
    assertEquals(0, completionCounter.get());
    completion.complete();
    assertTrue(completion.isCompleted());
    assertEquals(0, awaitCounter.get());
    assertEquals(1, completionCounter.get());
  }
}
