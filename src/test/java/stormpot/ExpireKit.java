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
package stormpot;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;

public class ExpireKit {
  public interface Expire {
    boolean hasExpired(SlotInfo<? extends Poolable> info) throws Exception;
  }

  public interface SlotInfoCapture {
    void capture(SlotInfo<? extends Poolable> info);
  }

  public interface CountingExpiration extends Expiration<GenericPoolable> {
    int countExpirations();
  }

  static class ExpireSeq {
    private final Expire[] checks;
    private int counter;

    ExpireSeq(Expire... checks) {
      this.checks = checks;
      counter = 0;
    }

    boolean checkNext(
        SlotInfo<? extends GenericPoolable> info) throws Exception {
      Expire expire = checks[counter];
      counter = Math.min(checks.length - 1, counter + 1);
      return expire.hasExpired(info);
    }
  }

  private static class CountingExpirationImpl implements CountingExpiration {
    private final ExpireSeq expires;
    private final AtomicInteger counts;

    private CountingExpirationImpl(Expire... checks) {
      expires = new ExpireSeq(checks);
      counts = new AtomicInteger();
    }

    @Override
    public int countExpirations() {
      return counts.get();
    }

    @Override
    public boolean hasExpired(
        SlotInfo<? extends GenericPoolable> info) throws Exception {
      counts.incrementAndGet();
      return expires.checkNext(info);
    }
  }

  public static CountingExpiration expire(Expire... checks) {
    return new CountingExpirationImpl(checks);
  }

  public static final Expire $fresh = info -> false;

  public static final Expire $expired = info -> true;

  public static final Expire $explicitExpire = info -> {
    GenericPoolable poolable = (GenericPoolable) info.getPoolable();
    poolable.expire();
    return false;
  };

  public static Expire $if(AtomicBoolean cond, Expire then, Expire otherwise) {
    return info -> cond.get() ?
        then.hasExpired(info) : otherwise.hasExpired(info);
  }

  public static Expire $expiredIf(AtomicBoolean cond) {
    return $if(cond, $expired, $fresh);
  }

  public static Expire $throwExpire(Throwable throwable) {
    return info -> {
      UnitKit.sneakyThrow(throwable);
      return false;
    };
  }

  public static Expire $countDown(CountDownLatch latch, Expire then) {
    return info -> {
      latch.countDown();
      return then.hasExpired(info);
    };
  }

  public static Expire $capture(
      final SlotInfoCapture capture,
      final Expire then) {
    return info -> {
      capture.capture(info);
      return then.hasExpired(info);
    };
  }

  public static SlotInfoCapture $age(AtomicLong age) {
    return info -> age.set(info.getAgeMillis());
  }

  public static SlotInfoCapture $poolable(AtomicReference<Poolable> ref) {
    return info -> ref.set(info.getPoolable());
  }

  public static SlotInfoCapture $claimCount(AtomicLong count) {
    return info -> count.set(info.getClaimCount());
  }
}
