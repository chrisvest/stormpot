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
package blackbox;

import extensions.FailurePrinterExtension;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import stormpot.*;

import javax.management.JMX;
import javax.management.MBeanServer;
import javax.management.MBeanServerFactory;
import javax.management.ObjectName;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;
import java.util.function.Function;

import static java.util.concurrent.TimeUnit.MILLISECONDS;
import static java.util.function.Function.identity;
import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.*;
import static stormpot.UnitKit.*;
import static stormpot.UnitKit.waitForThreadState;

@org.junit.jupiter.api.Timeout(42)
@ExtendWith(FailurePrinterExtension.class)
abstract class AbstractPoolTest<T extends Poolable> {
  static final Timeout longTimeout = new Timeout(5, TimeUnit.MINUTES);
  static final Timeout mediumTimeout = new Timeout(10, MILLISECONDS);
  static final Timeout shortTimeout = new Timeout(1, MILLISECONDS);
  static final Timeout zeroTimeout = new Timeout(0, MILLISECONDS);

  final Expiration<T> oneMsTTL = Expiration.after(1, MILLISECONDS);
  final Expiration<T> fiveMsTTL = Expiration.after(5, MILLISECONDS);
  private final Consumer<T> nullConsumer = (obj) -> {};

  Pool<T> pool;
  PoolTap<T> threadSafeTap;
  PoolTap<T> threadLocalTap;

  @AfterEach
  void shutPoolDown() throws InterruptedException {
    if (pool != null) {
      //noinspection ResultOfMethodCallIgnored
      Thread.interrupted(); // Clear the interrupt flag.
      String poolName = pool.getClass().getSimpleName();
      assertTrue(
          pool.shutdown().await(longTimeout),
          "Pool did not shut down within timeout: " + poolName);
    }
  }

  abstract void createOneObjectPool();

  abstract void noBackgroundExpirationChecking();

  abstract void createPoolOfSize(int size);

  @ParameterizedTest
  @EnumSource(Taps.class)
  void timeoutCannotBeNull(Taps taps) {
    createOneObjectPool();
    assertThrows(NullPointerException.class, () -> taps.get(this).claim(null));
  }

  /**
   * A call to claim must return before the timeout elapses if it
   * can claim an object from the pool, and it must return that object.
   * The timeout for the claim is longer than the timeout for the test, so we
   * know that we won't get a null back here because the timeout wasn't long
   * enough. If we do, then the pool does not correctly implement the timeout
   * behaviour.
   */
  @ParameterizedTest
  @EnumSource(Taps.class)
  void claimMustReturnIfWithinTimeout(Taps taps) throws Exception {
    createOneObjectPool();
    T obj = taps.get(this).claim(longTimeout);
    try {
      assertThat(obj).isNotNull();
    } finally {
      obj.release();
    }
  }

  /**
   * A call to claim that fails to get an object before the
   * timeout elapses, must return null.
   * We test this by depleting a pool, and then make a call to claim with
   * a shot timeout. If that call returns <code>null</code>, then we're good.
   */
  @ParameterizedTest
  @EnumSource(Taps.class)
  void claimMustReturnNullIfTimeoutElapses(Taps taps) throws Exception {
    createOneObjectPool();
    PoolTap<T> tap = taps.get(this);
    T a = tap.claim(longTimeout); // pool is now depleted
    Poolable b = tap.claim(shortTimeout);
    try {
      assertThat(b).isNull();
    } finally {
      a.release();
    }
  }

  /**
   * If the pool has been depleted for objects, then a call to claim with
   * timeout will wait until either an object becomes available, or the timeout
   * elapses. Whichever comes first.
   * We test for this by observing that a thread that makes a claim-with-timeout
   * call to a depleted pool, will enter the TIMED_WAITING state.
   */
  @ParameterizedTest
  @EnumSource(Taps.class)
  void blockingClaimWithTimeoutMustWaitIfPoolIsEmpty(Taps taps)
      throws Exception {
    createOneObjectPool();
    PoolTap<T> tap = taps.get(this);
    T obj = tap.claim(longTimeout);
    assertNotNull(obj, "Did not deplete pool in time");
    AtomicReference<T> ref = new AtomicReference<>();
    Thread thread = fork(capture($claim(tap, longTimeout), ref));
    try {
      waitForThreadState(thread, Thread.State.TIMED_WAITING);
    } finally {
      obj.release();
      thread.join();
      ref.get().release();
    }
  }

  /**
   * A thread that is waiting in claim-with-timeout on a depleted pool must
   * wake up if another thread releases an object back into the pool.
   * So if we deplete a pool, make a thread wait in claim-with-timeout and
   * then release an object back into the pool, then we must be able to join
   * to that thread.
   */
  @ParameterizedTest
  @EnumSource(Taps.class)
  void blockingOnClaimWithTimeoutMustResumeWhenPoolablesAreReleased(Taps taps)
      throws Exception {
    createOneObjectPool();
    PoolTap<T> tap = taps.get(this);
    Poolable obj = tap.claim(longTimeout);
    assertNotNull(obj, "Did not deplete pool in time");
    AtomicReference<T> ref = new AtomicReference<>();
    Thread thread = fork(capture($claim(tap, longTimeout), ref));
    waitForThreadState(thread, Thread.State.TIMED_WAITING);
    obj.release();
    join(thread);
    ref.get().release();
  }

  /**
   * One uses a pool because a certain type of objects are expensive to
   * create and we would like to recycle them. So when we claim and object,
   * then release it back into the pool, and then claim and release it again,
   * then we must observe that only a single object allocation has taken
   * place.
   * The pool has a size of 1, so we can safely base this test on the
   * allocation count - even for pools that like to eagerly saturate the
   * pool with objects.
   */
  @ParameterizedTest
  @EnumSource(Taps.class)
  void mustReuseAllocatedObjects(Taps taps) throws Exception {
    createOneObjectPool();
    PoolTap<T> tap = taps.get(this);
    T a = tap.claim(longTimeout);
    a.release();
    T b = tap.claim(longTimeout);
    b.release();
    assertThat(b).isSameAs(a);
  }

  /**
   * It is not possible to claim from a pool that has been shut down. Doing
   * so will cause an IllegalStateException to be thrown. This must take
   * effect as soon as shutdown has been called. So the fact that claim()
   * becomes unusable happens-before the pool shutdown process completes.
   * The memory effects of this are not tested for, but I don't think it is
   * possible to implement in a thread-safe manner and not provide the
   * memory effects that we want.
   */
  @ParameterizedTest
  @EnumSource(Taps.class)
  void preventClaimFromPoolThatIsShutDown(Taps taps) throws Exception {
    createOneObjectPool();
    PoolTap<T> tap = taps.get(this);
    tap.claim(longTimeout).release();
    pool.shutdown();
    assertThrows(IllegalStateException.class, () -> tap.claim(longTimeout));
  }

  /**
   * So awaiting the shut down completion cannot return before all
   * claimed objects are both released and deallocated. Likewise, the
   * initiation of the shut down process - the call to shutdown() - must
   * decidedly NOT wait for any claimed objects to be released, before the
   * call returns.
   * We test for this effect by creating a pool and claiming and object
   * without ever releasing it. Then we call shutdown, without ever awaiting
   * its completion. The test passes if this does not dead-lock, hence the
   * test timeout.
   */
  @ParameterizedTest
  @EnumSource(Taps.class)
  void shutdownCallMustReturnFastIfPoolablesAreStillClaimed(Taps taps) throws Exception {
    createOneObjectPool();
    PoolTap<T> tap = taps.get(this);
    T obj = tap.claim(longTimeout);
    try {
      pool.shutdown();
      assertNotNull(obj, "Did not deplete pool in time");
    } finally {
      obj.release();
    }
  }

  /**
   * We know from the
   * {@link PoolTest#shutdownMustNotDeallocateClaimedPoolables} test, that
   * awaiting the shut down completion will wait for any claimed objects to be
   * released.
   * However, once those objects are released, we must also make sure that the
   * shut down process actually resumes and eventually completes as a result.
   * We test this by claiming and object and starting the shut down process.
   * Then we set another thread to await the completion of the shut down
   * process, and make sure that it actually enters the WAITING state.
   * Then we release the claimed object and try to join the thread. If we
   * manage to join the thread, then the shut down process has completed, and
   * the test pass if this all happens within the test timeout.
   * When a thread is in the WAITING state, it means that it is waiting for
   * another thread to do something that will let it resume. In our case,
   * the thread is waiting for someone to release the claimed object.
   */
  @ParameterizedTest
  @EnumSource(Taps.class)
  void awaitOnShutdownMustReturnWhenClaimedObjectsAreReleased(Taps taps) throws Exception {
    createOneObjectPool();
    PoolTap<T> tap = taps.get(this);
    Poolable obj = tap.claim(longTimeout);
    Completion completion = pool.shutdown();
    Thread thread = fork($await(completion, longTimeout));
    waitForThreadState(thread, Thread.State.TIMED_WAITING);
    obj.release();
    join(thread);
  }

  /**
   * The await with timeout on the Completion of the shut down process
   * must return false if the timeout elapses, as is the typical contract
   * of such methods in java.util.concurrent.
   * We are going to assume that the implementation adheres to the requested
   * timeout within reasonable margins, because the implementations are
   * probably going to delegate this call to java.util.concurrent anyway.
   */
  @Test
  void awaitWithTimeoutMustReturnFalseIfTimeoutElapses() throws Exception {
    createOneObjectPool();
    T obj = pool.claim(longTimeout);
    assertFalse(pool.shutdown().await(shortTimeout));
    obj.release();
  }

  /**
   * We have verified that await with timeout returns false if the timeout
   * elapses. We also have to make sure that the call returns true if the
   * shut down process completes within the timeout.
   * We test for this by claiming an object, start the shut down process,
   * set a thread to await the completion with a timeout, then release the
   * claimed object and join the thread. The result will be put in an
   * AtomicBoolean, which then must contain true after the thread has been
   * joined. And this must all happen before the test itself times out.
   */
  @Test
  void awaitWithTimeoutMustReturnTrueIfCompletesWithinTimeout() throws Exception {
    createOneObjectPool();
    T obj = pool.claim(longTimeout);
    AtomicBoolean result = new AtomicBoolean(false);
    Completion completion = pool.shutdown();
    Thread thread =
        fork($await(completion, longTimeout, result));
    waitForThreadState(thread, Thread.State.TIMED_WAITING);
    obj.release();
    join(thread);
    assertTrue(result.get());
  }

  /**
   * We have verified that the await method works as intended, if you
   * begin your awaiting while the shut down process is still undergoing.
   * However, we must also make sure that further calls to await after the
   * shut down process has completed, do not block.
   * We do this by shutting a pool down, and then make a number of await calls
   * to the shut down Completion. These calls must all return before the
   * timeout of the test elapses.
   */
  @Test
  void awaitingOnAlreadyCompletedShutDownMustNotBlock() throws Exception {
    createOneObjectPool();
    Completion completion = pool.shutdown();
    completion.await(longTimeout);
    completion.await(longTimeout);
  }

  /**
   * A call to claim on a pool that has been, or is in the process of being,
   * shut down, will throw an IllegalStateException. So should calls that
   * are blocked on claim when the shut down process is initiated.
   * To test this, we create a pool with one object and claim it. Then we
   * set another thread to also claim an object. This thread will block
   * because the pool has been depleted. To make sure of this, we wait for
   * the thread to enter the WAITING state. Then we start the shut down
   * process of the pool, release the object and join the thread we started.
   * If the call to claim throws an exception in the other thread, then it
   * will be put in an AtomicReference, and we assert that it is indeed an
   * IllegalStateException.
   */
  @ParameterizedTest
  @EnumSource(Taps.class)
  void blockedClaimMustThrowWhenPoolIsShutDown(Taps taps) throws Exception {
    createOneObjectPool();
    PoolTap<T> tap = taps.get(this);
    AtomicReference<Exception> caught = new AtomicReference<>();
    Poolable obj = tap.claim(longTimeout);
    Thread thread = fork($catchFrom($claim(tap, longTimeout), caught));
    waitForThreadState(thread, Thread.State.TIMED_WAITING);
    pool.shutdown();
    obj.release();
    join(thread);
    assertThat(caught.get()).isInstanceOf(IllegalStateException.class);
  }

  /**
   * Calling await on a completion when your thread is interrupted, must
   * throw an InterruptedException.
   * In this particular case we make sure that the shut down procedure has
   * not yet completed, by claiming an object from the pool without releasing
   * it.
   */
  @Test
  void awaitOnCompletionWhenInterruptedMustThrow() throws Exception {
    createOneObjectPool();
    T obj = pool.claim(longTimeout);
    assertNotNull(obj, "Did not deplete pool in time");
    Completion completion = pool.shutdown();
    Thread.currentThread().interrupt();
    try {
      assertThrows(InterruptedException.class, () -> completion.await(longTimeout));
    } finally {
      obj.release();
    }
  }

  /**
   * A thread that is awaiting the completion of a shut down procedure with
   * a timeout, must throw an InterruptedException if it is interrupted.
   * We test this the same way we test without the timeout. The only difference
   * is that our thread will enter the TIMED_WAITING state because of the
   * timeout.
   */
  @Test
  void awaitWithTimeoutOnCompletionMustThrowUponInterruption() throws Exception {
    createOneObjectPool();
    T obj = pool.claim(longTimeout);
    assertNotNull(obj, "Did not deplete pool in time");
    Completion completion = pool.shutdown();
    forkFuture($interruptUponState(
        Thread.currentThread(), Thread.State.TIMED_WAITING));
    try {
      assertThrows(InterruptedException.class, () -> completion.await(longTimeout));
    } finally {
      obj.release();
    }
  }

  /**
   * As per the contract of throwing an InterruptedException, if the
   * await of an unfinished completion throws an InterruptedException, then
   * they must also clear the interrupted status.
   */
  @Test
  void awaitOnCompletionWhenInterruptedMustClearInterruption() throws Exception {
    try {
      awaitOnCompletionWhenInterruptedMustThrow();
    } catch (InterruptedException ignore) {}
    assertFalse(Thread.interrupted());

    try {
      awaitWithTimeoutOnCompletionMustThrowUponInterruption();
    } catch (InterruptedException ignore) {}
    assertFalse(Thread.interrupted());
  }

  private Completion givenFinishedInterruptedCompletion()
      throws InterruptedException {
    createOneObjectPool();
    pool.shutdown().await(longTimeout);
    Thread.currentThread().interrupt();
    return pool.shutdown();
  }

  /**
   * Calling await with a timeout on a finished completion when your thread
   * is interrupted must, just as with calling await without a timeout,
   * throw an InterruptedException.
   */
  @Test
  void awaitWithTimeoutOnFinishedCompletionWhenInterruptedMustThrow() throws InterruptedException {
    Completion completion = givenFinishedInterruptedCompletion();
    assertThrows(InterruptedException.class, () -> completion.await(longTimeout));
  }

  /**
   * As per the contract of throwing an InterruptedException, the above must
   * also clear the threads interrupted status.
   */
  @Test
  void awaitOnFinishedCompletionMustClearInterruption() {
    try {
      awaitWithTimeoutOnFinishedCompletionWhenInterruptedMustThrow();
    } catch (InterruptedException ignore) {}
    assertFalse(Thread.interrupted());
  }

  /**
   * Threads that are already interrupted upon entry to the claim method, must
   * not get an InterruptedException, unless they would actually end up blocking.
   * @see Pool
   */
  @ParameterizedTest
  @EnumSource(Taps.class)
  void claimWhenInterruptedMustNotThrowIfObjectIsImmediatelyAvailable(Taps taps) throws Exception {
    createOneObjectPool();
    PoolTap<T> tap = taps.get(this);
    // Ensure that the object is fully present in the pool.
    join(fork($claimRelease(tap, longTimeout)));
    // Then claim while being interrupted.
    Thread.currentThread().interrupt();
    tap.claim(longTimeout).release();
  }

  /**
   * Same as {@link #claimWhenInterruptedMustNotThrowIfObjectIsImmediatelyAvailable(Taps)},
   * but for when the object is available via thread-local cache.
   * @see Pool
   */
  @ParameterizedTest
  @EnumSource(Taps.class)
  void claimWhenInterruptedMustNotThrowIfObjectIsAvailableViaCache(Taps taps) throws Exception {
    createOneObjectPool();
    PoolTap<T> tap = taps.get(this);
    // Ensure that the object is fully present in the pool, and that the object is cached.
    tap.claim(longTimeout).release();
    // Then claim while being interrupted.
    Thread.currentThread().interrupt();
    tap.claim(longTimeout).release();
  }

  @ParameterizedTest
  @EnumSource(Taps.class)
  void claimWhenInterruptedMustThrowInterruptedExceptionImmediatelyWhenNoObjectIsAvailable(Taps taps)
      throws Exception {
    createOneObjectPool();
    PoolTap<T> tap = taps.get(this);
    CountDownLatch claimLatch = new CountDownLatch(1);
    Thread thread = fork(() -> {
      T obj = tap.claim(longTimeout);
      claimLatch.countDown();
      try {
        Thread.sleep(TimeUnit.MINUTES.toMillis(1));
      } catch (InterruptedException ignore) {
        // ignore
      } finally {
        obj.release();
      }
      return null;
    });
    claimLatch.await();
    Thread.currentThread().interrupt();
    try {
      assertThrows(InterruptedException.class, () -> tap.claim(longTimeout));
    } finally {
      thread.interrupt();
      join(thread);
    }
  }

  /**
   * The claim methods checks whether the current thread is interrupted upon
   * entry, but perhaps what is more important is the interruption of a claim
   * call that is already waiting when the thread is interrupted.
   * We test for this by setting a thread to interrupt us, when our thread
   * enters the WAITING or TIMED_WAITING states. Then we make a call to the
   * appropriate claim method. If it throws an InterruptException, then the
   * test passes.
   */
  @ParameterizedTest
  @EnumSource(Taps.class)
  void blockedClaimWithTimeoutMustThrowUponInterruption(Taps taps) throws Exception {
    createOneObjectPool();
    PoolTap<T> tap = taps.get(this);
    T a = tap.claim(longTimeout);
    assertNotNull(a, "Did not deplete pool in time");
    forkFuture($interruptUponState(
        Thread.currentThread(), Thread.State.TIMED_WAITING));
    try {
      assertThrows(InterruptedException.class, () -> tap.claim(longTimeout));
    } finally {
      a.release();
    }
  }

  /**
   * As per the general contract of interruptible methods, throwing an
   * InterruptedException will clear the interrupted flag on the thread.
   * This must also hold for the claim methods.
   */
  @ParameterizedTest
  @EnumSource(Taps.class)
  void throwingInterruptedExceptionFromClaimMustClearInterruptedFlag(Taps taps) throws Exception {
    createOneObjectPool();
    PoolTap<T> tap = taps.get(this);
    T a = tap.claim(longTimeout);
    assertNotNull(a, "Did not deplete pool in time");
    forkFuture($interruptUponState(
        Thread.currentThread(), Thread.State.TIMED_WAITING));
    try {
      assertThrows(InterruptedException.class, () -> tap.claim(longTimeout));
    } finally {
      a.release();
    }
    assertFalse(Thread.interrupted());
  }

  /**
   * When claim is called with a timeout less than one, then it means that
   * no (observable amount of) waiting should take place.
   * <p>
   * We test for this by going through the numbers 0 to 99, both inclusive,
   * and call claim with those numbers as timeout values. The test is
   * considered to have passed, if this process completes within the 42424
   * millisecond timeout on the test case.
   * @see Pool#claim(Timeout)
   */
  @ParameterizedTest
  @EnumSource(Taps.class)
  void claimWithTimeoutValueLessThanOneMustReturnImmediately(Taps taps) throws Exception {
    createOneObjectPool();
    PoolTap<T> tap = taps.get(this);
    T obj = tap.claim(longTimeout);
    assertNotNull(obj, "Did not deplete pool in time");
    try {
      tap.claim(zeroTimeout);
    } finally {
      obj.release();
    }
  }

  /**
   * When await is called on a shutdown Completion with a timeout value less
   * than one, then no amount of waiting must take place.
   * <p>
   * We test for this by depleting a pool and initiating the shut-down
   * procedure. The fact that the pool is depleted, means that the shutdown
   * procedure will not be able to finish. In other words, we know that it is
   * in progress for our subsequent await calls.
   * Then we call await on the shut-down Completion object, giving timeout
   * values ranging from zero to -99. These awaits must all complete before the
   * test itself times out.
   * @see Completion
   */
  @Test
  void awaitCompletionWithTimeoutLessThanOneMustReturnImmediately()
      throws Exception {
    createOneObjectPool();
    T obj = pool.claim(longTimeout);
    try {
      assertNotNull(obj, "Did not deplete pool in time");
      pool.shutdown().await(zeroTimeout);
    } finally {
      obj.release();
    }
  }

  /**
   * One must provide a Timeout argument when awaiting the completion of a
   * shut-down procedure. Passing null is thus an illegal argument.
   * @see Completion
   */
  @Test
  void awaitCompletionWithNullTimeUnitMustThrow() {
    createOneObjectPool();
    Completion completion = pool.shutdown();
    assertThrows(NullPointerException.class, () -> completion.await(null));
  }

  /**
   * Calling shutdown on a pool while being interrupted must still start the
   * shut-down procedure.
   * We test for this by initiating the shut-down procedure on a pool while
   * being interrupted. Then we clear our interrupted flag and await the
   * completion of the shut-down procedure. The procedure must complete within
   * the test timeout. If it does not, then that is taken as evidence that
   * the procedure did NOT start, and so the test fails.
   * @see Pool
   */
  @Test
  void mustBeAbleToShutDownEvenIfInterrupted() throws Exception {
    createOneObjectPool();
    Thread.currentThread().interrupt();
    Completion completion = pool.shutdown();
    assertTrue(Thread.interrupted()); // clear interrupted flag
    completion.await(longTimeout); // must complete before test timeout
  }

  /**
   * Initiating the shut-down procedure must not influence the threads
   * interruption status.
   * We test for this by calling shutdown on the pool while being interrupted.
   * Then we check that we are still interrupted.
   * @see Pool
   */
  @Test
  void callingShutdownMustNotAffectInterruptionStatus() {
    createOneObjectPool();
    Thread.currentThread().interrupt();
    pool.shutdown();
    assertTrue(Thread.interrupted());
  }

  /**
   * Pool implementations might do some kind of biasing to reduce contention.
   * We need to make sure that if there are not enough objects for all the
   * threads, then even biased objects must participate in the circulation.
   * If they don't, then some threads might starve.
   */
  @ParameterizedTest
  @EnumSource(Taps.class)
  void mustUnbiasObjectsNoLongerClaimed(Taps taps) throws Exception {
    createOneObjectPool();
    PoolTap<T> tap = taps.get(this);
    Poolable obj = tap.claim(longTimeout);
    obj.release(); // item now biased to our thread
    // claiming in a different thread should give us the same object.
    AtomicReference<T> ref =
        new AtomicReference<>();
    join(forkFuture(capture($claim(tap, longTimeout), ref)));
    try {
      assertThat(ref.get()).isSameAs(obj);
    } finally {
      ref.get().release();
    }
  }

  /**
   * Pool implementations that do biasing need to ensure, that claimed objects
   * are not available for other threads, even if the object is claimed with
   * a biased kind of claim.
   * In other words, if we claim an object, it might become biased to us. If we
   * then release it and then call claim again, we might go through a different
   * path through the code. And this new path needs to ensure that the object
   * is properly claimed, such that no other threads can claim it as well.
   */
  @ParameterizedTest
  @EnumSource(Taps.class)
  void biasedClaimMustUpgradeToOrdinaryClaimIfTheObjectIsPulledFromTheQueue(Taps taps)
      throws Exception {
    createOneObjectPool();
    PoolTap<T> tap = taps.get(this);
    tap.claim(longTimeout).release(); // bias the object to our thread
    T obj = tap.claim(longTimeout); // this is now our biased claim
    AtomicReference<T> ref = new AtomicReference<>();
    // the biased claim will be upgraded to an ordinary claim:
    join(forkFuture(capture($claim(tap, zeroTimeout), ref)));
    try {
      assertThat(ref.get()).isNull();
    } finally {
      obj.release();
    }
  }

  /**
   * If a pool has been depleted, and then shut down, and another call to claim
   * comes in, then it must immediately throw an IllegalStateException.
   * Importantly, it must not block the thread to wait for any objects to be
   * released.
   */
  @ParameterizedTest
  @EnumSource(Taps.class)
  void depletedPoolThatHasBeenShutDownMustThrowUponClaim(Taps taps) throws Exception {
    createOneObjectPool();
    PoolTap<T> tap = taps.get(this);
    T obj = pool.claim(longTimeout); // depleted
    pool.shutdown();
    try {
      assertThrows(IllegalStateException.class, () -> tap.claim(longTimeout));
    } finally {
      obj.release();
    }
  }

  /**
   * Basically the same test as above, except now we wait for the shutdown
   * process to make a bit of progress. This might expose different race bugs.
   */
  @ParameterizedTest
  @EnumSource(Taps.class)
  void depletedPoolThatHasBeenShutDownMustThrowUponClaimEvenAfterSomeTime(Taps taps)
      throws Exception {
    createOneObjectPool();
    PoolTap<T> tap = taps.get(this);
    T obj = pool.claim(longTimeout); // depleted
    pool.shutdown();
    spinwait(10);
    try {
      assertThrows(IllegalStateException.class, () -> tap.claim(longTimeout));
    } finally {
      obj.release();
    }
  }

  /**
   * We must ensure that, for pool implementation that do biasing, the checking
   * of whether the pool has been shut down must come before even a biased
   * claim. Even though a biased claim might not do any waiting that a normal
   * claim might do, it is still important that the shutdown notification takes
   * precedence, because we don't know for how long the claimed object will be
   * held, or if there will be other biased claims in the future.
   */
  @ParameterizedTest
  @EnumSource(Taps.class)
  void poolThatHasBeenShutDownMustThrowUponClaimEvenIfItHasAvailableUnbiasedObjects(Taps taps)
      throws Exception {
    createPoolOfSize(4);
    PoolTap<T> tap = taps.get(this);
    T a = tap.claim(longTimeout);
    T b = tap.claim(longTimeout);
    T c = tap.claim(longTimeout);
    T d = tap.claim(longTimeout);
    a.release(); // placed ahead of any poison pills
    pool.shutdown();
    b.release();
    c.release();
    d.release();
    assertThrows(IllegalStateException.class, () -> tap.claim(longTimeout));
  }

  /**
   * It is explicitly permitted that the thread that releases an object back
   * into the pool, can be a different thread than the one that claimed the
   * particular object.
   */
  @ParameterizedTest
  @EnumSource(Taps.class)
  void mustNotThrowWhenReleasingObjectClaimedByAnotherThread(Taps taps) throws Exception {
    createOneObjectPool();
    PoolTap<T> tap = taps.get(this);
    T obj = forkFuture($claim(tap, longTimeout)).get();
    obj.release();
  }

  @Test
  void targetSizeMustBeConfiguredSizeByDefault() {
    createPoolOfSize(23);
    assertThat(pool.getTargetSize()).isEqualTo(23);
  }

  @Test
  void managedPoolInterfaceMustBeMXBeanConformant() {
    assertTrue(JMX.isMXBeanInterface(ManagedPool.class));
  }

  @Test
  void managedPoolMustBeExposableThroughAnMBeanServerAsAnMXBean() throws Exception {
    createPoolOfSize(3);
    ManagedPool managedPool = pool.getManagedPool();
    T a = pool.claim(longTimeout);
    T b = pool.claim(longTimeout);
    T c = pool.claim(longTimeout);

    try {
      MBeanServer server = MBeanServerFactory.newMBeanServer("domain");
      ObjectName name = new ObjectName("domain:pool=stormpot");
      server.registerMBean(managedPool, name);
      ManagedPool proxy = JMX.newMBeanProxy(server, name, ManagedPool.class);

      // Loop a few times, since the count is updated after slots are added to
      // the live queue.
      for (int i = 0; i < 1000 && proxy.getAllocationCount() < 3; i++) {
        Thread.yield();
      }

      assertThat(proxy.getAllocationCount()).isEqualTo(3L);
    } finally {
      a.release();
      b.release();
      c.release();
    }
  }

  @Test
  void managedPoolMustCountAllocations() throws InterruptedException {
    createPoolOfSize(3);
    ManagedPool managedPool = pool.getManagedPool();

    List<T> objs = new ArrayList<>();
    for (int i = 0; i < 3; i++) {
      objs.add(pool.claim(longTimeout));
    }
    for (T obj : objs) {
      obj.release();
    }

    long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
    while (managedPool.getAllocationCount() < 3 && System.nanoTime() < deadline) {
      Thread.yield();
    }

    assertThat(managedPool.getAllocationCount()).isEqualTo(3);
  }

  @Test
  void managedPoolMustGivePoolState() throws Exception {
    createOneObjectPool();
    ManagedPool managedPool = pool.getManagedPool();
    assertFalse(managedPool.isShutDown());
    pool.shutdown().await(longTimeout);
    assertTrue(managedPool.isShutDown());
  }

  @ParameterizedTest
  @EnumSource(Taps.class)
  void applyMustThrowOnNullTimeout(Taps taps) {
    createOneObjectPool();
    PoolTap<T> tap = taps.get(this);
    assertThrows(NullPointerException.class, () -> tap.apply(null, identity()));
  }

  @ParameterizedTest
  @EnumSource(Taps.class)
  void supplyMustThrowOnNullTimeout(Taps taps) {
    createOneObjectPool();
    PoolTap<T> tap = taps.get(this);
    assertThrows(NullPointerException.class, () -> tap.supply(null, nullConsumer));
  }

  @ParameterizedTest
  @EnumSource(Taps.class)
  void applyMustThrowOnNullFunction(Taps taps) {
    createOneObjectPool();
    PoolTap<T> tap = taps.get(this);
    assertThrows(NullPointerException.class, () -> tap.apply(longTimeout, null));
  }

  @ParameterizedTest
  @EnumSource(Taps.class)
  void supplyMustThrowOnNullConsumer(Taps taps) {
    createOneObjectPool();
    PoolTap<T> tap = taps.get(this);
    assertThrows(NullPointerException.class, () -> tap.supply(longTimeout, null));
  }

  @ParameterizedTest
  @EnumSource(Taps.class)
  void applyMustReturnEmptyIfTimeoutElapses(Taps taps) throws Exception {
    createOneObjectPool();
    PoolTap<T> tap = taps.get(this);
    T obj = tap.claim(longTimeout);
    try {
      assertFalse(tap.apply(shortTimeout, identity()).isPresent());
    } finally {
      obj.release();
    }
  }

  @ParameterizedTest
  @EnumSource(Taps.class)
  void supplyMustReturnFalseIfTimeoutElapses(Taps taps) throws Exception {
    createOneObjectPool();
    PoolTap<T> tap = taps.get(this);
    T obj = tap.claim(longTimeout);
    try {
      assertFalse(tap.supply(shortTimeout, nullConsumer));
    } finally {
      obj.release();
    }
  }

  @ParameterizedTest
  @EnumSource(Taps.class)
  void applyMustNotCallFunctionIfTimeoutElapses(Taps taps) throws Exception {
    createOneObjectPool();
    PoolTap<T> tap = taps.get(this);
    T obj = tap.claim(longTimeout);
    try {
      AtomicInteger counter = new AtomicInteger();
      tap.apply(shortTimeout, (x) -> (Object) counter.incrementAndGet());
      assertThat(counter.get()).isZero();
    } finally {
      obj.release();
    }
  }

  @ParameterizedTest
  @EnumSource(Taps.class)
  void supplyMustNotCallConsumerIfTimeoutElapses(Taps taps) throws Exception {
    createOneObjectPool();
    PoolTap<T> tap = taps.get(this);
    T obj = tap.claim(longTimeout);
    try {
      AtomicReference<T> ref = new AtomicReference<>();
      tap.supply(shortTimeout, ref::set);
      assertThat(ref.get()).isNull();
    } finally {
      obj.release();
    }
  }

  @ParameterizedTest
  @EnumSource(Taps.class)
  void applyMustCallFunctionIfObjectClaimedWithinTimeout(Taps taps) throws Exception {
    createOneObjectPool();
    PoolTap<T> tap = taps.get(this);
    AtomicInteger counter = new AtomicInteger();
    tap.apply(longTimeout, (x) -> (Object) counter.incrementAndGet());
    assertThat(counter.get()).isOne();
  }

  @ParameterizedTest
  @EnumSource(Taps.class)
  void supplyMustCallConsumerIfObjectClaimedWithinTimeout(Taps taps) throws Exception {
    createOneObjectPool();
    PoolTap<T> tap = taps.get(this);
    T obj = tap.claim(longTimeout);
    obj.release();
    AtomicReference<T> ref = new AtomicReference<>();
    tap.supply(shortTimeout, ref::set);
    assertThat(ref.get()).isSameAs(obj);
  }

  @ParameterizedTest
  @EnumSource(Taps.class)
  void applyMustReturnResultOfFunction(Taps taps) throws Exception {
    createOneObjectPool();
    PoolTap<T> tap = taps.get(this);
    String expectedResult = "Result!";
    Optional<String> actualResult =
        tap.apply(longTimeout, (obj) -> expectedResult);
    assertTrue(actualResult.isPresent());
    assertThat(actualResult.get()).isEqualTo(expectedResult);
  }

  @ParameterizedTest
  @EnumSource(Taps.class)
  void applyMustReturnEmptyIfFunctionReturnsNull(Taps taps) throws Exception {
    createOneObjectPool();
    PoolTap<T> tap = taps.get(this);
    assertThat(tap.apply(longTimeout, (obj) -> null)).isEmpty();
  }

  @ParameterizedTest
  @EnumSource(Taps.class)
  void applyMustReleaseClaimedObject(Taps taps) throws Exception {
    createOneObjectPool();
    PoolTap<T> tap = taps.get(this);
    tap.apply(longTimeout, identity());
    tap.apply(longTimeout, identity());
    fork(() -> tap.apply(longTimeout, identity())).join();
    fork(() -> tap.apply(longTimeout, identity())).join();
    tap.apply(longTimeout, identity());
    tap.apply(longTimeout, identity());
  }

  @ParameterizedTest
  @EnumSource(Taps.class)
  void supplyMustReleaseClaimedObject(Taps taps) throws Exception {
    createOneObjectPool();
    PoolTap<T> tap = taps.get(this);
    tap.supply(longTimeout, nullConsumer);
    tap.supply(longTimeout, nullConsumer);
    fork(() -> tap.supply(longTimeout, nullConsumer)).join();
    fork(() -> tap.supply(longTimeout, nullConsumer)).join();
    tap.supply(longTimeout, nullConsumer);
    tap.supply(longTimeout, nullConsumer);
  }

  private static Object expectException(Callable<?> callable) {
    try {
      callable.call();
      fail("The ExpectedException was not thrown");
    } catch (ExpectedException ignore) {
      // We expect this
    } catch (Exception e) {
      throw new AssertionError("Failed for other reason", e);
    }
    return null;
  }

  @ParameterizedTest
  @EnumSource(Taps.class)
  void applyMustReleaseClaimedObjectEvenIfFunctionThrows(Taps taps) throws Exception {
    createOneObjectPool();
    PoolTap<T> tap = taps.get(this);
    Function<T,Object> thrower = (obj) -> {
      throw new ExpectedException();
    };

    expectException(() -> tap.apply(longTimeout, thrower));
    expectException(() -> tap.apply(longTimeout, thrower));
    fork(() -> expectException(() -> tap.apply(longTimeout, thrower))).join();
    fork(() -> expectException(() -> tap.apply(longTimeout, thrower))).join();
    expectException(() -> tap.apply(longTimeout, thrower));
    expectException(() -> tap.apply(longTimeout, thrower));
  }

  @ParameterizedTest
  @EnumSource(Taps.class)
  void supplyMustReleaseClaimedObjectEvenIfConsumerThrows(Taps taps) throws Exception {
    createOneObjectPool();
    PoolTap<T> tap = taps.get(this);
    Consumer<T> thrower = (obj) -> {
      throw new ExpectedException();
    };

    expectException(() -> tap.supply(longTimeout, thrower));
    expectException(() -> tap.supply(longTimeout, thrower));
    fork(() -> expectException(() -> tap.supply(longTimeout, thrower))).join();
    fork(() -> expectException(() -> tap.supply(longTimeout, thrower))).join();
    expectException(() -> tap.supply(longTimeout, thrower));
    expectException(() -> tap.supply(longTimeout, thrower));
  }

  @ParameterizedTest
  @EnumSource(Taps.class)
  void applyMustThrowIfThePoolIsShutDown(Taps taps) throws Exception {
    createOneObjectPool();
    PoolTap<T> tap = taps.get(this);
    pool.shutdown().await(longTimeout);
    assertThrows(IllegalStateException.class, () -> tap.apply(longTimeout, identity()));
  }

  @ParameterizedTest
  @EnumSource(Taps.class)
  void supplyMustThrowIfThePoolIsShutDown(Taps taps) throws Exception {
    createOneObjectPool();
    PoolTap<T> tap = taps.get(this);
    pool.shutdown().await(longTimeout);
    assertThrows(IllegalStateException.class, () -> tap.supply(longTimeout, nullConsumer));
  }

  @ParameterizedTest
  @EnumSource(Taps.class)
  void shuttingPoolDownMustUnblockApplyAndThrow(Taps taps) throws Throwable {
    createOneObjectPool();
    PoolTap<T> tap = taps.get(this);
    T obj = tap.claim(longTimeout);
    Thread thread = fork(() -> tap.apply(longTimeout, identity()));
    AtomicReference<Throwable> exception = capture(thread);
    waitForThreadState(thread, Thread.State.TIMED_WAITING);
    Completion shutdown = pool.shutdown();
    join(thread);
    obj.release();
    shutdown.await(longTimeout);
    assertThat(exception.get()).isInstanceOf(IllegalStateException.class);
  }

  @ParameterizedTest
  @EnumSource(Taps.class)
  void shuttingPoolDownMustUnblockSupplyAndThrow(Taps taps) throws Throwable {
    createOneObjectPool();
    PoolTap<T> tap = taps.get(this);
    T obj = tap.claim(longTimeout);
    Thread thread = fork(() -> tap.supply(longTimeout, nullConsumer));
    AtomicReference<Throwable> exception = capture(thread);
    waitForThreadState(thread, Thread.State.TIMED_WAITING);
    Completion shutdown = pool.shutdown();
    join(thread);
    obj.release();
    shutdown.await(longTimeout);
    assertThat(exception.get()).isInstanceOf(IllegalStateException.class);
  }

  @ParameterizedTest
  @EnumSource(Taps.class)
  void applyMustThrowOnInterrupt(Taps taps) throws Exception {
    createOneObjectPool();
    PoolTap<T> tap = taps.get(this);
    // Exhaust the pool to ensure the next claim is blocking.
    T obj = tap.claim(longTimeout);
    Thread.currentThread().interrupt();
    try {
      assertThrows(InterruptedException.class, () -> tap.apply(longTimeout, identity()));
    } finally {
      obj.release();
    }
  }

  @ParameterizedTest
  @EnumSource(Taps.class)
  void supplyMustThrowOnInterrupt(Taps taps) throws Exception {
    createOneObjectPool();
    PoolTap<T> tap = taps.get(this);
    // Exhaust the pool to ensure the next claim is blocking.
    T obj = tap.claim(longTimeout);
    Thread.currentThread().interrupt();
    try {
      assertThrows(InterruptedException.class, () -> tap.supply(longTimeout, nullConsumer));
    } finally {
      obj.release();
    }
  }

  @ParameterizedTest
  @EnumSource(Taps.class)
  void blockedApplyMustThrowOnInterrupt(Taps taps) throws Throwable {
    createOneObjectPool();
    PoolTap<T> tap = taps.get(this);
    T obj = tap.claim(longTimeout);
    Thread thread = fork(() -> tap.apply(longTimeout, identity()));
    AtomicReference<Throwable> exception = capture(thread);
    waitForThreadState(thread, Thread.State.TIMED_WAITING);
    thread.interrupt();
    join(thread);
    obj.release();
    assertThat(exception.get()).isInstanceOf(InterruptedException.class);
  }

  @ParameterizedTest
  @EnumSource(Taps.class)
  void blockedSupplyMustThrowOnInterrupt(Taps taps) throws Throwable {
    createOneObjectPool();
    PoolTap<T> tap = taps.get(this);
    T obj = tap.claim(longTimeout);
    Thread thread = fork(() -> tap.supply(longTimeout, nullConsumer));
    AtomicReference<Throwable> exception = capture(thread);
    waitForThreadState(thread, Thread.State.TIMED_WAITING);
    thread.interrupt();
    join(thread);
    obj.release();
    assertThat(exception.get()).isInstanceOf(InterruptedException.class);
  }

  @ParameterizedTest
  @EnumSource(Taps.class)
  void applyMustUnblockByConcurrentRelease(Taps taps) throws Exception {
    createOneObjectPool();
    PoolTap<T> tap = taps.get(this);
    T obj = pool.claim(longTimeout);
    Thread thread = fork(() -> tap.apply(longTimeout, identity()));
    waitForThreadState(thread, Thread.State.TIMED_WAITING);
    obj.release();
    join(thread);
  }

  @ParameterizedTest
  @EnumSource(Taps.class)
  void supplyMustUnblockByConcurrentRelease(Taps taps) throws Exception {
    createOneObjectPool();
    PoolTap<T> tap = taps.get(this);
    T obj = pool.claim(longTimeout);
    Thread thread = fork(() -> tap.supply(longTimeout, nullConsumer));
    waitForThreadState(thread, Thread.State.TIMED_WAITING);
    obj.release();
    join(thread);
  }

  @Test
  void threadLocalTapsCacheIndependentObjects() throws Exception {
    noBackgroundExpirationChecking();
    createPoolOfSize(2);
    PoolTap<T> tap1 = pool.getThreadLocalTap();
    PoolTap<T> tap2 = pool.getThreadLocalTap();
    T a = tap1.claim(longTimeout);
    T b = tap2.claim(longTimeout);
    a.release();
    b.release();
    T c = tap1.claim(longTimeout);
    c.release();
    T d = tap2.claim(longTimeout);
    d.release();
    assertThat(c).isSameAs(a);
    assertThat(d).isSameAs(b);
  }

  @Test
  void threadLocalAndThreadSafeTapsCacheIndependentObjects() throws Exception {
    createPoolOfSize(2);
    PoolTap<T> tap1 = pool.getThreadLocalTap();
    PoolTap<T> tap2 = pool.getThreadSafeTap();
    T a = tap1.claim(longTimeout);
    T b = tap2.claim(longTimeout);
    a.release();
    b.release();
    T c = tap1.claim(longTimeout);
    c.release();
    T d = tap2.claim(longTimeout);
    d.release();
    assertThat(c).isSameAs(a);
    assertThat(d).isSameAs(b);
  }

  @Test
  void threadSafeTapsCacheThreadBoundObjects() throws Exception {
    createPoolOfSize(2);
    PoolTap<T> tap1 = pool.getThreadSafeTap();
    PoolTap<T> tap2 = pool.getThreadSafeTap();
    T a = tap1.claim(longTimeout);
    T b = tap2.claim(longTimeout);
    a.release();
    b.release();
    T c = tap1.claim(longTimeout);
    c.release();
    assertThat(c).isSameAs(b);
  }
}
