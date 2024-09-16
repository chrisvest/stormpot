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

import java.lang.invoke.MethodHandles;
import java.lang.invoke.VarHandle;

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
  private static final VarHandle ARRAY_VH;
  private static final VarHandle STRIPES_VH;

  static {
    ARRAY_VH = MethodHandles.arrayElementVarHandle(BSlotCache[].class)
            .withInvokeExactBehavior();
    assert ARRAY_VH.hasInvokeExactBehavior();
    try {
      STRIPES_VH = MethodHandles.lookup().findVarHandle(
              BlazePoolVirtualThreadSafeTap.class, "stripes", BSlotCache[].class)
              .withInvokeExactBehavior();
      assert STRIPES_VH.hasInvokeExactBehavior();
    } catch (Exception e) {
      throw new ExceptionInInitializerError(e);
    }
  }

  private static final int ARRAY_SIZE = 0x80; // 128
  private static final int ARRAY_MASK = 0x7F;

  private final BlazePool<T> pool;
  @SuppressWarnings("unused") // Accessed via VarHandle
  private BSlotCache<T>[] stripes;

  BlazePoolVirtualThreadSafeTap(BlazePool<T> pool) {
    this.pool = pool;
  }

  @Override
  public T claim(Timeout timeout) throws PoolException, InterruptedException {
    BSlotCache<T>[] stripes = getStripes();
    long threadId = Thread.currentThread().threadId();
    T obj = tryTlrClaim(threadId, stripes);
    if (obj != null) {
      return obj;
    }
    int index = (int) (threadId & ARRAY_MASK);
    BSlotCache<T> cache = stripes[index];
    assert cache != null;
    return pool.claim(timeout, cache);
  }

  private BSlotCache<T>[] getStripes() {
    BSlotCache<T>[] stripes = this.stripes;
    if (stripes == null) {
      stripes = createStripes();
    }
    return stripes;
  }

  @SuppressWarnings("unchecked")
  private BSlotCache<T>[] createStripes() {
    BSlotCache<T>[] stripes, witness;
    stripes = (BSlotCache<T>[]) STRIPES_VH.getVolatile(this);
    if (stripes == null) {
      stripes = new BSlotCache[ARRAY_SIZE];
      witness = (BSlotCache<T>[]) STRIPES_VH.compareAndExchange(this, (BSlotCache<T>[]) null, stripes);
      if (witness != null) {
        stripes = witness;
      }
    }
    return stripes;
  }

  private T tryTlrClaim(long threadId, BSlotCache<T>[] stripes) {
    for (int i = 0; i < 4; i++) {
      int index = (int) (threadId + i & ARRAY_MASK);
      BSlotCache<T> cache = stripes[index];
      if (cache == null) {
        cache = createCacheSlot(stripes, index);
      }
      T obj = pool.tlrClaim(cache);
      if (obj != null) {
        return obj;
      }
    }
    return null;
  }

  @SuppressWarnings("unchecked")
  private static <T extends Poolable> BSlotCache<T> createCacheSlot(BSlotCache<T>[] stripes, int index) {
    BSlotCache<T> cache = new BSlotCache<>();
    BSlotCache<T> tmp = (BSlotCache<T>) ARRAY_VH.compareAndExchange(stripes, index, (BSlotCache<T>) null, cache);
    if (tmp != null) {
      cache = tmp;
    }
    return cache;
  }

  @Override
  public T tryClaim() throws PoolException {
    BSlotCache<T>[] stripes = getStripes();
    long threadId = Thread.currentThread().threadId();
    T obj = tryTlrClaim(threadId, stripes);
    if (obj != null) {
      return obj;
    }
    int index = (int) (threadId & ARRAY_MASK);
    BSlotCache<T> cache = stripes[index];
    assert cache != null;
    return pool.tryClaim(cache);
  }
}
