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

import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TestRule;

import static java.util.Arrays.asList;
import static java.util.Collections.singletonList;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.sameInstance;
import static org.junit.Assert.assertThat;
import static stormpot.AlloKit.*;

public class ReallocatingAdaptorTest {
  private CountingAllocator allocator;
  private ReallocatingAdaptor<GenericPoolable> reallocator;

  @Rule public final TestRule failurePrinter = new FailurePrinterTestRule();

  @Before public void
  setUp() {
    allocator = allocator();
    reallocator = new ReallocatingAdaptor<>(allocator);
  }

  @Test
  public void
  mustBeAssignableAsAllocator() {
    Config<GenericPoolable> config = new Config<>();
    config.setAllocator(reallocator);
    Allocator<GenericPoolable> expectedInstance = reallocator;
    assertThat(config.getAllocator(), sameInstance(expectedInstance));
  }

  @Test public void
  allocateMustDelegate() throws Exception {
    GenericPoolable obj = reallocator.allocate(new NullSlot());
    assertThat(allocator.getAllocations(), is(singletonList(obj)));
    assertThat(allocator.countAllocations(), is(1));
    assertThat(allocator.countDeallocations(), is(0));
  }

  @Test public void
  deallocateMustDelegate() throws Exception {
    GenericPoolable obj = reallocator.allocate(new NullSlot());
    reallocator.deallocate(obj);
    assertThat(allocator.getDeallocations(), is(singletonList(obj)));
    assertThat(allocator.countAllocations(), is(1));
    assertThat(allocator.countDeallocations(), is(1));
  }

  @Test public void
  reallocateMustDelegate() throws Exception {
    Slot slot = new NullSlot();
    GenericPoolable obj1 = reallocator.allocate(slot);
    GenericPoolable obj2 = reallocator.reallocate(slot, obj1);
    assertThat(allocator.getAllocations(), is(asList(obj1, obj2)));
    assertThat(allocator.getDeallocations(), is(singletonList(obj1)));
    assertThat(allocator.countAllocations(), is(2));
    assertThat(allocator.countDeallocations(), is(1));
  }

  @Test public void
  reallocateMustSwallowExceptionsThrownByDeallocate() throws Exception {
    allocator = allocator(
        dealloc($throw(new AssertionError("Should have caught this"))));
    reallocator = new ReallocatingAdaptor<>(allocator);
    Slot slot = new NullSlot();
    GenericPoolable obj = reallocator.allocate(slot);
    reallocator.reallocate(slot, obj);
  }

  @Test public void
  unwrapMustReturnDelegate() {
    Allocator<GenericPoolable> allocatorInstance = allocator;
    assertThat(reallocator.unwrap(), sameInstance(allocatorInstance));
  }
}
