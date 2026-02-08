/*
 * Copyright © Chris Vest (mr.chrisvest@gmail.com)
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
package stormpot.tests;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.RepeatedTest;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;
import stormpot.Completion;
import stormpot.Timeout;
import stormpot.internal.StackCompletion;
import stormpot.tests.extensions.ExecutorExtension;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.Flow;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.locks.LockSupport;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;
import static testkits.UnitKit.capture;
import static testkits.UnitKit.waitForThreadState;

class StackCompletionTest {
  static final Timeout longTimeout = new Timeout(5, TimeUnit.MINUTES);

  @RegisterExtension
  final ExecutorExtension executor = new ExecutorExtension();

  protected Completion completion;

  @BeforeEach
  void setUp() {
    completion = new StackCompletion();
  }

  void complete() {
    ((StackCompletion) completion).complete();
  }

  @Test
  void mustBlockThreadUntilCompletion() throws Exception {
    Thread th = executor.fork(() -> {
      assertTrue(completion.await(longTimeout));
      return null;
    });
    AtomicReference<Throwable> exception = capture(th);
    waitForThreadState(th, Thread.State.TIMED_WAITING);
    complete();
    th.join();
    assertThat(exception).hasNullValue();
  }

  @Test
  void isCompleteMustReflectCompletion() {
    assertFalse(completion.isCompleted());
    assertFalse(completion.isReleasable());
    complete();
    assertTrue(completion.isCompleted());
    assertTrue(completion.isReleasable());
  }

  @Test
  void completionMustNotifySubscribers() throws Exception {
    AtomicInteger counter = new AtomicInteger();
    completion.subscribe(new MySubscriber(counter::incrementAndGet));
    CyclicBarrier barrier = new CyclicBarrier(10);
    List<Future<Object>> futures = new ArrayList<>();
    for (int i = 0; i < barrier.getParties(); i++) {
      futures.add(executor.forkFuture(() -> {
        barrier.await();
        complete();
        return null;
      }));
    }
    for (Future<Object> future : futures) {
      future.get();
    }
    assertEquals(1, counter.get());
  }

  @Test
  void completionMustNotNotifyCancelledSubscribers() throws Exception {
    AtomicInteger counter = new AtomicInteger();
    completion.subscribe(new MySubscriber(counter::incrementAndGet) {
      @Override
      public void onSubscribe(Flow.Subscription subscription) {
        subscription.cancel();
      }
    });
    CyclicBarrier barrier = new CyclicBarrier(10);
    List<Future<Object>> futures = new ArrayList<>();
    for (int i = 0; i < barrier.getParties(); i++) {
      futures.add(executor.forkFuture(() -> {
        barrier.await();
        complete();
        return null;
      }));
    }
    for (Future<Object> future : futures) {
      future.get();
    }
    assertEquals(0, counter.get());
  }

  @Test
  void completionMustNotifyNewSubscribersAfterCompletion() {
    complete();
    AtomicInteger counter = new AtomicInteger();
    completion.subscribe(new MySubscriber(counter::incrementAndGet));
    assertEquals(1, counter.get());
  }

  @Test
  void subscriptionCancelMustBeIdempotent() {
    AtomicInteger counter = new AtomicInteger();
    completion.subscribe(new MySubscriber(counter::incrementAndGet) {
      @Override
      public void onSubscribe(Flow.Subscription subscription) {
        subscription.cancel();
        subscription.cancel();
      }
    });
    complete();
    assertEquals(0, counter.get());
  }

  @Test
  void requestOnCancelledSubscriptionMustDoNothing() {
    AtomicInteger counter = new AtomicInteger();
    completion.subscribe(new MySubscriber(counter::incrementAndGet) {
      @Override
      public void onSubscribe(Flow.Subscription subscription) {
        subscription.cancel();
        subscription.request(1);
      }
    });
    complete();
    assertEquals(0, counter.get());
  }

  @Test
  void completedCompletionMustNotNotifySubscribersThatCancelImmediately() {
    AtomicInteger counter = new AtomicInteger();
    complete();
    completion.subscribe(new MySubscriber(counter::incrementAndGet) {
      @Override
      public void onSubscribe(Flow.Subscription subscription) {
        subscription.cancel();
      }
    });
    assertEquals(0, counter.get());
  }

  @Test
  void mustNotifySubscriberRequestingZeroMessages() {
    AtomicReference<Throwable> onError = new AtomicReference<>();
    AtomicInteger counter = new AtomicInteger();
    completion.subscribe(new MySubscriber(counter::incrementAndGet) {
      @Override
      public void onSubscribe(Flow.Subscription subscription) {
        subscription.request(0);
        subscription.request(0);
      }

      @Override
      public void onError(Throwable throwable) {
        assertNull(onError.getAndSet(throwable));
      }
    });
    assertThat(onError.get()).hasMessageContaining("positive number");
  }

  @Test
  void mustNotifySubscriberRequestingNegativeMessages() {
    AtomicReference<Throwable> onError = new AtomicReference<>();
    AtomicInteger counter = new AtomicInteger();
    completion.subscribe(new MySubscriber(counter::incrementAndGet) {
      @Override
      public void onSubscribe(Flow.Subscription subscription) {
        subscription.request(-1);
        subscription.request(-1);
      }

      @Override
      public void onError(Throwable throwable) {
        assertNull(onError.getAndSet(throwable));
      }
    });
    assertThat(onError.get()).hasMessageContaining("positive number");
  }

  @Test
  void managedBlockMustUnblockByCompletion() {
    Thread thread = executor.fork(() -> {
      while (!completion.block()) {
        Thread.onSpinWait();
      }
      assertTrue(completion.isReleasable());
      return null;
    });
    waitForThreadState(thread, Thread.State.WAITING);
    complete();
  }

  @Test
  void managedBlockMustAllowSpuriousUnblock() throws Exception {
    Thread thread = executor.fork(() -> {
      assertFalse(completion.block());
      return null;
    });
    AtomicReference<Throwable> capture = capture(thread);
    waitForThreadState(thread, Thread.State.WAITING);
    LockSupport.unpark(thread);
    thread.join();
    assertThat(capture).hasNullValue();
    complete();
  }

  @RepeatedTest(50)
  void mustCopeWithAllActionsRacingTogether() throws Exception {
    CyclicBarrier barrier = new CyclicBarrier(9);
    List<Thread> threads = new ArrayList<>();
    List<AtomicReference<Throwable>> exceptions = new ArrayList<>();
    AtomicInteger countCompletions = new AtomicInteger();
    AtomicInteger countNoCompletions = new AtomicInteger();
    AtomicInteger countAwaits = new AtomicInteger();

    for (int i = 0; i < 2; i++) {
      Thread thread = executor.fork(() -> {
        barrier.await();
        complete();
        return null;
      });
      exceptions.add(capture(thread));
      threads.add(thread);
    }

    for (int i = 0; i < 2; i++) {
      Thread thread = executor.fork(() -> {
        barrier.await();
        completion.subscribe(new MySubscriber(countCompletions::incrementAndGet));
        return null;
      });
      exceptions.add(capture(thread));
      threads.add(thread);
    }

    for (int i = 0; i < 2; i++) {
      Thread thread = executor.fork(() -> {
        barrier.await();
        completion.subscribe(new MySubscriber(countNoCompletions::incrementAndGet) {
          @Override
          public void onSubscribe(Flow.Subscription subscription) {
            subscription.cancel();
          }
        });
        return null;
      });
      exceptions.add(capture(thread));
      threads.add(thread);
    }

    for (int i = 0; i < 2; i++) {
      Thread thread = executor.fork(() -> {
        barrier.await();
        completion.await(new Timeout(1, TimeUnit.MINUTES));
        countAwaits.incrementAndGet();
        return null;
      });
      exceptions.add(capture(thread));
      threads.add(thread);
    }

    barrier.await();
    for (Thread thread : threads) {
      thread.join();
    }

    try {
      assertEquals(2, countCompletions.get());
      assertThat(countNoCompletions).hasValueBetween(0, 2); // The cancel and onComplete methods can race.
      assertEquals(2, countAwaits.get());
    } catch (Throwable e) {
      for (AtomicReference<Throwable> exception : exceptions) {
        Throwable suppressed = exception.get();
        if (suppressed != null) {
          e.addSuppressed(suppressed);
        }
      }
      throw e;
    }

    Throwable e = null;
    for (AtomicReference<Throwable> exception : exceptions) {
      Throwable throwable = exception.get();
      if (throwable != null) {
        if (e == null) {
          e = throwable;
        } else {
          e.addSuppressed(throwable);
        }
      }
    }
    if (e != null) {
      fail("Forked thread got exception", e);
    }
  }

  @Test
  void mustNotifyWhenResubscribingAfterCancel() {
    AtomicInteger counter = new AtomicInteger();
    MySubscriber subscriber = new MySubscriber(counter::incrementAndGet) {
      boolean first = true;

      @Override
      public void onSubscribe(Flow.Subscription subscription) {
        if (first) {
          subscription.cancel();
          first = false;
        }
        super.onSubscribe(subscription);
      }
    };
    completion.subscribe(subscriber);
    completion.subscribe(subscriber);
    complete();
    assertEquals(1, counter.get());
  }

  @Test
  void mustNotifyWhenSubscribeCompletes() {
    AtomicInteger counter = new AtomicInteger();
    MySubscriber subscriber = new MySubscriber(counter::incrementAndGet) {
      @Override
      public void onSubscribe(Flow.Subscription subscription) {
        complete();
        super.onSubscribe(subscription);
      }
    };
    completion.subscribe(subscriber);
    assertEquals(1, counter.get());
  }

  @Test
  void mustNotifyWhenResubscribeCompletes() {
    AtomicInteger counter = new AtomicInteger();
    MySubscriber subscriber = new MySubscriber(counter::incrementAndGet) {
      boolean first = true;

      @Override
      public void onSubscribe(Flow.Subscription subscription) {
        if (first) {
          subscription.cancel();
          first = false;
        } else {
          complete();
        }
        super.onSubscribe(subscription);
      }
    };
    completion.subscribe(subscriber);
    completion.subscribe(subscriber);
    assertEquals(1, counter.get());
  }
}
