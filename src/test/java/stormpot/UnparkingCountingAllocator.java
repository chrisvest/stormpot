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

import java.util.concurrent.locks.LockSupport;

final class UnparkingCountingAllocator extends
    CountingAllocator {
  private final Thread main;

  UnparkingCountingAllocator(Thread main) {
    this.main = main;
  }

  @Override
  public GenericPoolable allocate(Slot slot) throws Exception {
    try {
      return super.allocate(slot);
    } finally {
      LockSupport.unpark(main);
    }
  }

  @Override
  public void deallocate(GenericPoolable poolable) throws Exception {
    try {
      super.deallocate(poolable);
    } finally {
      LockSupport.unpark(main);
    }
  }
}