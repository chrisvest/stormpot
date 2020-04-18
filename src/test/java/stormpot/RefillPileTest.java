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

import org.junit.jupiter.api.Test;

import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RefillPileTest {
    private AtomicInteger poisonedSlots = new AtomicInteger();

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
}
