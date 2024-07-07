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
import org.openjdk.jol.info.ClassLayout;
import stormpot.Pool;
import stormpot.Pooled;
import stormpot.Slot;
import stormpot.Timeout;
import stormpot.internal.BSlot;
import testkits.AlloKit;
import testkits.GenericPoolable;

import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

class BSlotTest {
  // Blindly assume that cache lines are always 64 bytes wide.
  // Architectures where this is not the case, will just have to suffer.
  private static final int CACHE_LINE_SIZE = 64;

  @Test
  void slotObjectsShouldBeCacheLineAligned() throws Exception {
    String arch = System.getProperty("os.arch");
    if (arch.equals("x86_64") || arch.equals("amd64")) { // Only enable this on 64-bit machines.
        Pool<GenericPoolable> pool = Pool.fromInline(AlloKit.allocator()).setSize(1).build();
      GenericPoolable object = pool.claim(new Timeout(1, TimeUnit.SECONDS));
      Slot slot = object.getSlot();
      ClassLayout classLayout = ClassLayout.parseInstance(slot);
      long size = classLayout.instanceSize();
      String description = "BSlot instance size should be an even multiple of 64, " +
          "but was " + size + ", which is " + (size % CACHE_LINE_SIZE) + " bytes too big:" +
          System.lineSeparator() + classLayout.toPrintable();
      assertThat(size % CACHE_LINE_SIZE).as(description).isEqualTo(0);
    }
  }

  @Test
  void toStringForBSlot() {
    BSlot<Pooled<String>> slot = new BSlot<>(null, null);
    assertThat(slot.toString()).isEqualTo("BSolt[DEAD, obj = null, poison = null]");
    slot.obj = new Pooled<>(slot, "poke");
    slot.dead2live();
    assertThat(slot.toString()).isEqualTo("BSolt[LIVING, obj = Pooled[poke], poison = null]");
    slot.live2claimTlr();
    assertThat(slot.toString()).isEqualTo("BSolt[TLR_CLAIMED, obj = Pooled[poke], poison = null]");
    slot.claimTlr2live();
    slot.live2claim();
    assertThat(slot.toString()).isEqualTo("BSolt[CLAIMED, obj = Pooled[poke], poison = null]");
    slot.claim2dead();
    slot.obj = null;
    slot.poison = new Exception("boo");
    assertThat(slot.toString()).isEqualTo(
        "BSolt[DEAD, obj = null, poison = java.lang.Exception: boo]");
  }
}
