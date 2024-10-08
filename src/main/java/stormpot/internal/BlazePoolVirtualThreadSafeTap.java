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
package stormpot.internal;

import stormpot.PoolException;
import stormpot.PoolTap;
import stormpot.Poolable;
import stormpot.Timeout;

/**
 * The claim method in this pool tap offers similar thread-safety and
 * performance to the default thread-safe pool tap, but without the use of
 * {@link ThreadLocal}.
 * <p>
 * This allows the pool tap to be used by virtual threads, without risking the
 * undue memory overhead of thousands or millions of thread-locals.
 *
 * @param <T> The poolable type.
 */
public final class BlazePoolVirtualThreadSafeTap<T extends Poolable> implements PoolTap<T> {
  private static final int ARRAY_SIZE = 0x80; // 128
  private static final int ARRAY_MASK = 0x7F;

  private final BlazePool<T> pool;
  @SuppressWarnings("unused") // Accessed via VarHandle
  private final BSlotCache<T>[] stripes;

  /**
   * Create a virtual-thread-safe tap for the given pool.
   * @param pool The pool to tap from.
   * @param optimizeForMemory {@code true} to optimise for lower memory usage,
   *                                     otherwise {@code false} to prioritize performance.
   */
  @SuppressWarnings({"unchecked", "rawtypes"})
  public BlazePoolVirtualThreadSafeTap(BlazePool<T> pool, boolean optimizeForMemory) {
    this.pool = pool;
    stripes = new BSlotCache[ARRAY_SIZE];
    for (int i = 0; i < ARRAY_SIZE; i++) {
      stripes[i] = optimizeForMemory ? new BSlotCache<>() : new BSlotCachePadded<>();
    }
  }

  @Override
  public T claim(Timeout timeout) throws PoolException, InterruptedException {
    int threadId = getThreadId();
    T obj = tryTlrClaim(threadId, stripes);
    if (obj != null) {
      return obj;
    }
    int index = threadId & ARRAY_MASK;
    BSlotCache<T> cache = stripes[index];
    assert cache != null;
    return pool.claim(timeout, cache);
  }

  private static int getThreadId() {
    long threadId = Thread.currentThread().threadId();
    return (int) (threadId ^ threadId >> 7);
  }

  private T tryTlrClaim(int threadId, BSlotCache<T>[] stripes) {
    pool.checkShutDown();
    for (int i = 0; i < 4; i++) {
      int index = threadId + i & ARRAY_MASK;
      BSlotCache<T> cache = stripes[index];
      T obj = pool.tlrClaim(cache);
      if (obj != null) {
        return obj;
      }
    }
    return null;
  }

  @Override
  public T tryClaim() throws PoolException {
    int threadId = getThreadId();
    T obj = tryTlrClaim(threadId, stripes);
    if (obj != null) {
      return obj;
    }
    int index = threadId & ARRAY_MASK;
    BSlotCache<T> cache = stripes[index];
    assert cache != null;
    return pool.tryClaim(cache);
  }
}
