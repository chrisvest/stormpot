/*
 * Copyright © 2011-2019 Chris Vest (mr.chrisvest@gmail.com)
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
package blackbox.slow;

import org.junit.jupiter.api.Test;
import stormpot.ExpireKit;
import stormpot.GenericPoolable;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static stormpot.AlloKit.*;
import static stormpot.ExpireKit.$expiredIf;
import static stormpot.ExpireKit.expire;

abstract class ThreadBasedPoolIT extends PoolIT {

  @org.junit.jupiter.api.Timeout(160)
  @Test
  void backgroundExpirationMustDoNothingWhenPoolIsDepleted() throws Exception {
    AtomicBoolean hasExpired = new AtomicBoolean();
    ExpireKit.CountingExpiration expiration = expire($expiredIf(hasExpired));
    builder.setExpiration(expiration);
    builder.setBackgroundExpirationEnabled(true);

    createPool();

    // Do a thread-local reclaim, if applicable, to keep the object in
    // circulation
    pool.claim(longTimeout).release();
    GenericPoolable obj = pool.claim(longTimeout);
    int expirationsCount = expiration.countExpirations();

    hasExpired.set(true);

    Thread.sleep(1000);

    assertThat(allocator.countDeallocations()).isZero();
    assertThat(expiration.countExpirations()).isEqualTo(expirationsCount);
    obj.release();
  }

  @Test
  void backgroundExpirationMustNotFailWhenThereAreNoObjectsInCirculation()
      throws Exception {
    AtomicBoolean hasExpired = new AtomicBoolean();
    ExpireKit.CountingExpiration expiration = expire($expiredIf(hasExpired));
    builder.setExpiration(expiration);
    builder.setBackgroundExpirationEnabled(true);

    createPool();

    GenericPoolable obj = pool.claim(longTimeout);
    int expirationsCount = expiration.countExpirations();

    hasExpired.set(true);

    Thread.sleep(1000);

    assertThat(allocator.countDeallocations()).isZero();
    assertThat(expiration.countExpirations()).isEqualTo(expirationsCount);
    obj.release();
  }

  @org.junit.jupiter.api.Timeout(160)
  @Test
  void decreasingSizeOfDepletedPoolMustOnlyDeallocateAllocatedObjects()
      throws Exception {
    int startingSize = 256;
    CountDownLatch startLatch = new CountDownLatch(startingSize);
    Semaphore semaphore = new Semaphore(0);
    allocator = allocator(
        alloc($countDown(startLatch, $new)),
        dealloc($release(semaphore, $null)));
    builder.setSize(startingSize).setBackgroundExpirationCheckDelay(10);
    builder.setAllocator(allocator);
    createPool();
    startLatch.await();
    List<GenericPoolable> objs = new ArrayList<>();
    for (int i = 0; i < startingSize; i++) {
      objs.add(pool.claim(longTimeout));
    }

    int size = startingSize;
    List<GenericPoolable> subList = objs.subList(0, startingSize - 1);
    for (GenericPoolable obj : subList) {
      size--;
      pool.setTargetSize(size);
      // It's important that the wait mask produces values greater than the
      // allocation threads idle wait time.
      assertFalse(semaphore.tryAcquire(size & 127, TimeUnit.MILLISECONDS));
      obj.release();
      semaphore.acquire();
    }

    assertThat(allocator.getDeallocations()).containsExactlyElementsOf(subList);

    objs.get(startingSize - 1).release();
  }
}
