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

class ReallocatingAdaptor<T extends Poolable> implements Reallocator<T> {
  private final Allocator<T> allocator;

  public ReallocatingAdaptor(Allocator<T> allocator) {
    this.allocator = allocator;
  }

  @Override
  public T reallocate(Slot slot, T poolable) throws Exception {
    try {
      allocator.deallocate(poolable);
    } catch (Throwable ignore) {
      // ignored as per specification
    }
    return allocator.allocate(slot);
  }

  @Override
  public T allocate(Slot slot) throws Exception {
    return allocator.allocate(slot);
  }

  @Override
  public void deallocate(T poolable) throws Exception {
    allocator.deallocate(poolable);
  }

  public Allocator<T> unwrap() {
    return allocator;
  }
}
