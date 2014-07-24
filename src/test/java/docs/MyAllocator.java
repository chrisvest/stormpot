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
package docs;

// tag::myallocator[]
// MyAllocator.java - minimum Allocator implementation
import stormpot.Allocator;
import stormpot.Slot;

public class MyAllocator implements Allocator<MyPoolable> {
  public MyPoolable allocate(Slot slot) throws Exception {
    return new MyPoolable(slot);
  }

  public void deallocate(MyPoolable poolable) throws Exception {
    // Nothing to do here
    // But it's a perfect place to close sockets, files, etc.
  }
}
// end::myallocator[]
