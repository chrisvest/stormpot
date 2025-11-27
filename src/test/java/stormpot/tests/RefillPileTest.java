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
package stormpot.tests;

import stormpot.tests.extensions.ExecutorExtension;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;
import stormpot.Pooled;
import stormpot.internal.BSlot;
import stormpot.internal.RefillPile;
import testkits.GenericPoolable;

import java.util.HashSet;
import java.util.Set;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.atomic.AtomicLong;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RefillPileTest {
    @RegisterExtension
    static final ExecutorExtension EXECUTOR_EXTENSION = new ExecutorExtension();
    private final AtomicLong poisonedSlots = new AtomicLong();
    private ExecutorService executor;

    @BeforeEach
    void setUp() {
        executor = EXECUTOR_EXTENSION.getExecutorService();
    }

    @Test
    void pushAndRefill() {
        BlockingQueue<BSlot<GenericPoolable>> queue = new ArrayBlockingQueue<>(10);
        RefillPile<GenericPoolable> pile = new RefillPile<>(queue);
        BSlot<GenericPoolable> a = new BSlot<>(queue, poisonedSlots);
        BSlot<GenericPoolable> b = new BSlot<>(queue, poisonedSlots);
        pile.push(a);
        pile.push(b);
        assertTrue(pile.refill());
        assertFalse(pile.refill());
        assertThat(queue.poll()).isSameAs(b);
        assertThat(queue.poll()).isSameAs(a);
        assertThat(queue.poll()).isNull();
        assertThat(pile.pop()).isNull();
    }

    @Test
    void pushAndPop() {
        BlockingQueue<BSlot<GenericPoolable>> queue = new ArrayBlockingQueue<>(10);
        RefillPile<GenericPoolable> pile = new RefillPile<>(queue);
        BSlot<GenericPoolable> a = new BSlot<>(queue, poisonedSlots);
        BSlot<GenericPoolable> b = new BSlot<>(queue, poisonedSlots);
        BSlot<GenericPoolable> c = new BSlot<>(queue, poisonedSlots);
        pile.push(a);
        pile.push(b);
        assertThat(pile.pop()).isSameAs(b);
        pile.push(c);
        assertThat(pile.pop()).isSameAs(c);
        assertThat(pile.pop()).isSameAs(a);
        assertThat(pile.pop()).isNull();
        assertFalse(pile.refill());
    }

    @Test
    void pushAndPopConsistency() throws Exception {
        int iterations = 100_000;
        CountDownLatch start = new CountDownLatch(2);
        Set<Integer> observedInts = new HashSet<>();
        BlockingQueue<BSlot<Pooled<Integer>>> queue = new ArrayBlockingQueue<>(10);
        RefillPile<Pooled<Integer>> pile = new RefillPile<>(queue);
        var pusher = executor.submit(() -> {
            start.countDown();
            start.await();
            for (int i = 0; i < iterations; i++) {
                BSlot<Pooled<Integer>> slot = new BSlot<>(queue, poisonedSlots);
                slot.obj = new Pooled<>(slot, i);
                pile.push(slot);
            }
            return null;
        });
        var popper = executor.submit(() -> {
            start.countDown();
            start.await();
            for (int i = 0; i < iterations; i++) {
                BSlot<Pooled<Integer>> slot;
                do {
                    slot = pile.pop();
                } while (slot == null);
                assertTrue(observedInts.add(slot.obj.object));
            }
            return null;
        });
        pusher.get();
        popper.get();
        assertThat(observedInts.size()).isEqualTo(iterations);
    }

    @Test
    void toStringOfEmptyRefillPile() {
        BlockingQueue<BSlot<GenericPoolable>> queue = new ArrayBlockingQueue<>(10);
        RefillPile<GenericPoolable> pile = new RefillPile<>(queue);
        assertThat(pile.toString()).contains("EMPTY");
    }

    @Test
    void toStringOfNonEmptyRefillPile() {
        BlockingQueue<BSlot<GenericPoolable>> queue = new ArrayBlockingQueue<>(10);
        RefillPile<GenericPoolable> pile = new RefillPile<>(queue);
        BSlot<GenericPoolable> a = new BSlot<>(queue, poisonedSlots);
        pile.push(a);
        assertThat(pile.toString()).contains(a.toString());
    }
}
