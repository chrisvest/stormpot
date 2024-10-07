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
  
  public StackCompletion() {
    this(null);
  }

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
        } while (next == null && ++i < 128);
        Thread.yield();
        next = ns.next;
      } while (next == null);
    }
    return next;
  }

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
              ns.compareAndSetObj(subscriber, new CancelledSubscription(subscriber))) {
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
      if (obj instanceof CancelledSubscription cs && cs.subscriber == subscriber) {
        if (ns.compareAndSetObj(cs, subscriber)) {
          subscriber.onSubscribe(ns);
          if (isCompleted() && ns.compareAndSetObj(subscriber, new CancelledSubscription(subscriber))) {
            subscriber.onComplete();
          }
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
    Node setNext = node;
    node.obj = subscriber;

    if (onAwait != null) {
      setNext = new Node();
      setNext.obj = new Awaitable(this, onAwait);
      node.next = setNext;
    }

    Node existing = (Node) NODES.getAndSet(this, node);
    setNext.next = existing;
    subscriber.onSubscribe(node);
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
      Node next = loadNext(this);
      if (n <= 0 && obj instanceof Flow.Subscriber<?> subscriber) {
        cancel();
        subscriber.onError(new IllegalArgumentException("Must request a positive number of objects"));
        return;
      }
      if (next != END && next != DONE) {
        if (next.obj instanceof Awaitable awaitable) {
          try {
            awaitable.await();
          } catch (InterruptedException e) {
            if (obj instanceof Flow.Subscriber<?> subscriber) {
              subscriber.onError(e);
            } else {
              Thread.currentThread().interrupt();
            }
          }
        }
      }
    }

    @SuppressWarnings("unchecked")
    @Override
    public void cancel() {
      Flow.Subscriber<Void> subscriber;
      CancelledSubscription cancelled;
      do {
        Object curr = obj;
        if (curr instanceof Flow.Subscriber<?>) {
          subscriber = (Flow.Subscriber<Void>) curr;
          cancelled = new CancelledSubscription(subscriber);
        } else if (curr instanceof CancelledSubscription) {
          return; // Already cancelled
        } else {
          throw new IllegalStateException("Not a subscription node");
        }
      } while (!compareAndSetObj(subscriber, cancelled));
    }
  }

  private record CancelledSubscription(Flow.Subscriber<?> subscriber) {
  }

  public interface OnAwait {
    boolean await(Timeout timeout) throws InterruptedException;
  }

  private record Awaitable(StackCompletion completion, OnAwait onAwait) {
    void await() throws InterruptedException {
      if (onAwait.await(null)) {
        completion.complete();
      }
    }
  }
}
