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

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

public class CountingAllocator implements Allocator<GenericPoolable> {
  protected final AtomicInteger allocations = new AtomicInteger();
  protected final AtomicInteger deallocations = new AtomicInteger();
  protected final List<GenericPoolable> allocated =
    Collections.synchronizedList(new ArrayList<GenericPoolable>());
  protected final List<GenericPoolable> deallocated =
    Collections.synchronizedList(new ArrayList<GenericPoolable>());

  public GenericPoolable allocate(Slot slot) throws Exception {
    assert slot != null : "Slot cannot be null in allocate";
    allocations.incrementAndGet();
    GenericPoolable obj = new GenericPoolable(slot);
    allocated.add(obj);
    return obj;
  }

  public void deallocate(GenericPoolable poolable) throws Exception {
    assert poolable != null : "Cannot deallocate null.";
    deallocations.incrementAndGet();
    deallocated.add(poolable);
  }
  
  public int allocations() {
    return allocations.get();
  }
  
  public int deallocations() {
    return deallocations.get();
  }

  public List<GenericPoolable> allocationList() {
    return allocated;
  }

  public List<GenericPoolable> deallocationList() {
    return deallocated;
  }
}
