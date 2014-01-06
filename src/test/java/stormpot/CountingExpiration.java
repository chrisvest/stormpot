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

import java.util.concurrent.atomic.AtomicInteger;

/**
 * An Expiration that counts its calls and returns pre-programmed responses.
 * @author Chris Vest &lt;mr.chrisvest@gmail.com&gt;
 */
public class CountingExpiration implements Expiration<Poolable> {
  private final boolean[] replies;
  private final AtomicInteger counter;

  public CountingExpiration(boolean... replies) {
    this.replies = replies;
    counter = new AtomicInteger();
  }

  @Override
  public boolean hasExpired(SlotInfo<? extends Poolable> info) {
    int count = counter.getAndIncrement();
    if (count == Integer.MAX_VALUE) {
      counter.set(0);
    }
    int index = Math.min(count, replies.length - 1);
    return replies[index];
  }

  public int getCount() {
    return counter.get();
  }
}
