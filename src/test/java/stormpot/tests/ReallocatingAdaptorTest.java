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

import stormpot.tests.extensions.FailurePrinterExtension;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import stormpot.Allocator;
import stormpot.Pool;
import stormpot.PoolBuilder;
import stormpot.Slot;
import stormpot.internal.ReallocatingAdaptor;
import testkits.GenericPoolable;
import testkits.NullSlot;

import static org.assertj.core.api.Assertions.assertThat;
import static testkits.AlloKit.$throw;
import static testkits.AlloKit.CountingAllocator;
import static testkits.AlloKit.allocator;
import static testkits.AlloKit.dealloc;

@ExtendWith(FailurePrinterExtension.class)
class ReallocatingAdaptorTest {
  private CountingAllocator allocator;
  private ReallocatingAdaptor<GenericPoolable> reallocator;

  @BeforeEach
  void setUp() {
    allocator = allocator();
    reallocator = new ReallocatingAdaptor<>(allocator);
  }

  @Test
  void mustBeAssignableAsAllocator() {
    PoolBuilder<GenericPoolable> builder = Pool.from(reallocator);
    assertThat(builder.getAllocator()).isSameAs(reallocator);
  }

  @Test
  void allocateMustDelegate() throws Exception {
    GenericPoolable obj = reallocator.allocate(new NullSlot());
    assertThat(allocator.getAllocations()).containsExactly(obj);
    assertThat(allocator.countAllocations()).isOne();
    assertThat(allocator.countDeallocations()).isZero();
  }

  @Test
  void deallocateMustDelegate() throws Exception {
    GenericPoolable obj = reallocator.allocate(new NullSlot());
    reallocator.deallocate(obj);
    assertThat(allocator.getDeallocations()).containsExactly(obj);
    assertThat(allocator.countAllocations()).isOne();
    assertThat(allocator.countDeallocations()).isOne();
  }

  @Test
  void reallocateMustDelegate() throws Exception {
    Slot slot = new NullSlot();
    GenericPoolable obj1 = reallocator.allocate(slot);
    GenericPoolable obj2 = reallocator.reallocate(slot, obj1);
    assertThat(allocator.getAllocations()).containsExactly(obj1, obj2);
    assertThat(allocator.getDeallocations()).containsExactly(obj1);
    assertThat(allocator.countAllocations()).isEqualTo(2);
    assertThat(allocator.countDeallocations()).isOne();
  }

  @Test
  void reallocateMustSwallowExceptionsThrownByDeallocate() throws Exception {
    allocator = allocator(
        dealloc($throw(new AssertionError("Should have caught this"))));
    reallocator = new ReallocatingAdaptor<>(allocator);
    Slot slot = new NullSlot();
    GenericPoolable obj = reallocator.allocate(slot);
    reallocator.reallocate(slot, obj);
  }

  @Test
  void unwrapMustReturnDelegate() {
    Allocator<GenericPoolable> allocatorInstance = allocator;
    assertThat(reallocator.unwrap()).isSameAs(allocatorInstance);
  }
}
