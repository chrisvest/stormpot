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

public class FallibleReallocator extends CountingReallocator {
  private final boolean[] replies;
  private final Exception exception;
  private int counter;

  public FallibleReallocator(Exception exception, boolean... replies) {
    this.replies = replies;
    this.exception = exception;
    counter = 0;
  }

  @Override
  public GenericPoolable reallocate(Slot slot, GenericPoolable poolable) throws Exception {
    boolean reply = replies[counter];
    counter = Math.min(replies.length - 1, counter + 1);
    if (!reply) {
      throw exception;
    }
    return super.reallocate(slot, poolable);
  }

  @Override
  public GenericPoolable allocate(Slot slot) throws Exception {
    boolean reply = replies[counter];
    counter = Math.min(replies.length - 1, counter + 1);
    if (!reply) {
      throw exception;
    }
    return super.allocate(slot);
  }
}
