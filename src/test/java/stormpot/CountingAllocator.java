/*
 * Copyright 2011 Chris Vest
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
package stormpot;

import java.util.concurrent.atomic.AtomicInteger;

public class CountingAllocator implements Allocator {
  private final AtomicInteger allocations = new AtomicInteger();
  private final AtomicInteger deallocations = new AtomicInteger();

  public Poolable allocate(Slot slot) throws Exception {
    allocations.incrementAndGet();
    return new GenericPoolable(slot);
  }

  public void deallocate(Poolable poolable) throws Exception {
    deallocations.incrementAndGet();
  }

  public void reset() {
    allocations.set(0);
    deallocations.set(0);
  }
  
  public int allocations() {
    return allocations.get();
  }
  
  public int deallocations() {
    return deallocations.get();
  }
}
