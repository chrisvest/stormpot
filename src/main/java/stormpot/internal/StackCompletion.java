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

import stormpot.Completion;
import stormpot.Timeout;

import java.lang.invoke.MethodHandles;
import java.lang.invoke.VarHandle;
import java.util.Objects;
import java.util.concurrent.Flow;
import java.util.concurrent.locks.LockSupport;

/**
 * An implementation of {@link Completion} based on a wait-free stack.
 * This implementation also supports a callback mechanism that trigger every time
 * an {@link #await(Timeout)} or {@link #block()} call is about to block.
 */
public final class StackCompletion implements Completion {
  private static final Node END = new Node("END");
  private static final Node DONE = new Node("DONE");
  private static final VarHandle NODES;
  private static final VarHandle NODE_OBJ;
  static {
    MethodHandles.Lookup lookup = MethodHandles.lookup();
    try {
      NODES = lookup.findVarHandle(StackCompletion.class, "nodes", Node.class).withInvokeExactBehavior();
      NODE_OBJ = lookup.findVarHandle(Node.class, "obj", Object.class).withInvokeExactBehavior();
    } catch (Exception e) {
      throw new ExceptionInInitializerError(e);
    }
  }

  @SuppressWarnings("FieldMayBeFinal") // Accessed via VarHandle
  private volatile Node nodes;
  private final OnAwait onAwait;

  /**
   * Create an unfinished completion with no {@link OnAwait} callback.
   */
  public StackCompletion() {
    this(false);
  }

  /**
   * Create a possibly finished completion with no {@link OnAwait} callback.
   * @param completed {@code true} if the completion should be initialized in the finished state,
   *                              otherwise {@code false}.
   */
  public StackCompletion(boolean completed) {
    nodes = completed ? DONE : END;
    onAwait = null;
  }

  /**
   * Create an unfinished completion with the given {@link OnAwait} callback.
   * @param onAwait The callback to notify for blocking, may be {@code null}.
   */
  public StackCompletion(OnAwait onAwait) {
    nodes = END;
    this.onAwait = onAwait;
  }

  private static Node loadNext(Node ns) {
    Node next = ns.next;
    if (next == null) {
      do {
        int i = 0;
        do {
          Thread.onSpinWait();
          next = ns.next;
        } while (next == null && ++i < 256);
        Thread.yield();
        next = ns.next;
      } while (next == null);
    }
    return next;
  }

  /**
   * Complete this completion and notify all waiters and subscribers.
   */
  public void complete() {
    if (nodes == DONE) {
      return;
    }

    Node ns = (Node) NODES.getAndSet(this, DONE);
    while (ns != END && ns != DONE) {
      Node next = loadNext(ns);
      Object obj = ns.obj;
      if (obj instanceof Thread th) {
        LockSupport.unpark(th);
      } else if (obj instanceof Flow.Subscriber<?> subscriber &&
              ns.compareAndSetObj(subscriber, null)) {
        subscriber.onComplete();
      }
      ns = next;
    }
  }

  @Override
  public void subscribe(Flow.Subscriber<? super Void> subscriber) {
    Objects.requireNonNull(subscriber, "Subscriber cannot be null.");
    if (isCompleted()) {
      oneOffCompleteSubscriber(subscriber);
      return;
    }

    Node ns = nodes;
    while (ns != END && ns != DONE) {
      Node next = loadNext(ns);
      Object obj = ns.obj;
      if (obj == null && ns.compareAndSetObj(null, subscriber)) {
        subscriber.onSubscribe(ns);
        if (isCompleted() && ns.compareAndSetObj(subscriber, null)) {
          subscriber.onComplete();
        }
        return;
      }
      ns = next;
    }

    if (isCompleted()) {
      oneOffCompleteSubscriber(subscriber);
      return;
    }

    Node node = new Node();
    node.obj = subscriber;
    subscriber.onSubscribe(node);
    Node existing = (Node) NODES.getAndSet(this, node);
    node.next = existing;
    if (existing == DONE) {
      complete();
    }
  }

  private static void oneOffCompleteSubscriber(Flow.Subscriber<? super Void> subscriber) {
    Node node = new Node();
    node.obj = subscriber;
    node.next = DONE;
    subscriber.onSubscribe(node);
    if (node.obj == subscriber) {
      subscriber.onComplete();
    }
  }

  /**
   * Complete the given completion when this completion completes. If the given completion completes first,
   * then their subscription to this completion is cancelled.
   * @param other The completion to propagate to.
   */
  public void propagateTo(StackCompletion other) {
    if (other.isCompleted()) {
      return;
    }
    if (isCompleted()) {
      other.complete();
    }
    subscribe(new BaseSubscriber() {
      @Override
      public void onSubscribe(Flow.Subscription subscription) {
        super.onSubscribe(subscription);
        other.subscribe(new BaseSubscriber() {
          @Override
          public void onComplete() {
            subscription.cancel();
          }
        });
      }

      @Override
      public void onComplete() {
        other.complete();
      }
    });
  }

  @Override
  public boolean await(Timeout timeout) throws InterruptedException {
    Objects.requireNonNull(timeout, "Timeout cannot be null.");
    return awaitInner(timeout);
  }

  private boolean awaitInner(Timeout timeout) throws InterruptedException {
    if (Thread.interrupted()) {
      throw new InterruptedException();
    }
    if (isCompleted()) {
      return true;
    }

    long start = timeout == null ? 0 : NanoClock.nanoTime();
    long timeoutNanos = timeout == null ? 0 : timeout.getTimeoutInBaseUnit();

    Node ns = nodes;
    while (ns != END && ns != DONE) {
      Node next = loadNext(ns);
      Object obj = ns.obj;
      if (obj == null && ns.compareAndSetObj(null, Thread.currentThread())) {
        if (nodes == DONE) {
          return true;
        } else {
          return awaitCompletion(start, timeoutNanos, ns);
        }
      }
      ns = next;
    }

    if (isCompleted()) {
      return true;
    }

    if (onAwait != null) {
      boolean completed = onAwait.await(timeout);
      if (completed) {
        complete();
      }
      return completed;
    }

    Node node = new Node();
    node.obj = Thread.currentThread();
    Node existing = (Node) NODES.getAndSet(this, node);
    node.next = existing;
    if (existing == DONE) {
      complete();
      return true;
    }

    return awaitCompletion(start, timeoutNanos, node);
  }

  private boolean awaitCompletion(long start, long timeoutNanos, Node node) throws InterruptedException {
    boolean oneShot = start == 0 && timeoutNanos == 0;
    long nanos = oneShot ? 1 : NanoClock.timeoutLeft(start, timeoutNanos);
    while (nanos > 0) {
      if (oneShot) {
       LockSupport.park(this);
      } else {
        LockSupport.parkNanos(this, nanos);
      }
      if (Thread.interrupted()) {
        throw new InterruptedException();
      } else if (nodes == DONE) {
        return true;
      } else {
        nanos = oneShot ? 0 : NanoClock.timeoutLeft(start, timeoutNanos);
      }
    }
    node.obj = null;
    return false;
  }

  @Override
  public boolean block() throws InterruptedException {
    return awaitInner(null);
  }

  @Override
  public boolean isReleasable() {
    return isCompleted();
  }

  @Override
  public boolean isCompleted() {
    Node ns = nodes;
    while (ns != END) {
      if (ns == DONE) {
        return true;
      }
      ns = loadNext(ns);
    }
    return false;
  }

  private static class Node implements Flow.Subscription {
    volatile Node next;
    volatile Object obj;

    Node() {
    }

    Node(String mark) {
      obj = mark;
    }

    @Override
    public String toString() {
      return "Node(" + obj + (next == null ? ")" : ", " + next + ")");
    }

    boolean compareAndSetObj(Object expected, Object update) {
      return NODE_OBJ.compareAndSet(this, expected, update);
    }

    @Override
    public void request(long n) {
      if (n <= 0 && obj instanceof Flow.Subscriber<?> subscriber) {
        cancel();
        subscriber.onError(new IllegalArgumentException("Must request a positive number of objects"));
      }
    }

    @SuppressWarnings("unchecked")
    @Override
    public void cancel() {
      Flow.Subscriber<Void> subscriber;
      do {
        Object curr = obj;
        if (curr instanceof Flow.Subscriber<?>) {
          subscriber = (Flow.Subscriber<Void>) curr;
        } else {
          return;
        }
      } while (!compareAndSetObj(subscriber, null));
    }
  }

  /**
   * A callback that will be notified when threads block on a given completion.
   */
  @FunctionalInterface
  public interface OnAwait {
    /**
     * Implements the blocking await of a {@link StackCompletion}.
     * @param timeout A timeout to bound the waiting. May be {@code null}.
     * @return {@code true} if the completion completed, otherwise {@code false}.
     * @throws InterruptedException If the thread was interrupted while waiting.
     */
    boolean await(Timeout timeout) throws InterruptedException;
  }
}
