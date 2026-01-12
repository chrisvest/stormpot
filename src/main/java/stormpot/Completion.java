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
package stormpot;

import stormpot.internal.StackCompletion;

import java.util.concurrent.CompletionStage;
import java.util.concurrent.Flow;
import java.util.concurrent.ForkJoinPool;
import java.util.concurrent.ForkJoinPool.ManagedBlocker;
import java.util.function.Consumer;

/**
 * A Completion represents some task that is going to be completed at some
 * point in the future, or maybe already has completed. It is similar to
 * {@link java.util.concurrent.Future Future} but without any options for
 * cancellation or returning a result.
 * <p>
 * Completions implement the {@link ManagedBlocker} interface, so they can
 * be safely used with {@link ForkJoinPool#managedBlock(ManagedBlocker)}.
 * <p>
 * Completions are also {@link Flow.Publisher} of {@link Void}.
 * No messages or exceptions are ever published, but the
 * {@link Flow.Subscriber#onComplete()} methods will be called when the
 * completion is completed.
 * If the completion has already completed, then the
 * {@link Flow.Subscriber#onComplete()} method will be called immediately
 * from {@link java.util.concurrent.Flow.Publisher#subscribe(Flow.Subscriber)}.
 *
 * @author Chris Vest
 * @see Pool#shutdown()
 */
public interface Completion extends ManagedBlocker, Flow.Publisher<Void> {
  /**
   * Translate the given {@link CompletionStage} into a Stormpot {@code Completion}.
   *
   * @param stage The {@link CompletionStage}.
   * @return A {@link Completion} that completes when the given stage completes.
   */
  static Completion from(CompletionStage<?> stage) {
    StackCompletion completion = new StackCompletion();
    stage.whenComplete((v, e) -> completion.complete());
    return completion;
  }

  /**
   * Create a completion using the given callback handler.
   * The {@link Consumer} argument will be given a {@link Runnable} instance,
   * which will complete the {@link Completion} when the {@link Runnable#run()} method is called.
   *
   * @param completionCallback The handler controlling the completion.
   * @return A {@link Completion} that completes when the given {@link Runnable#run()} method is called.
   */
  static Completion from(Consumer<Runnable> completionCallback) {
    StackCompletion completion = new StackCompletion();
    completionCallback.accept(completion::complete);
    return completion;
  }
  
  /**
   * Causes the current thread to wait until the completion is finished,
   * or the thread is {@link Thread#interrupt() interrupted}, or the specified
   * waiting time elapses.
   * <p>
   * If the task represented by this completion has already completed,
   * the method immediately returns {@code true}.
   * <p>
   * If the current thread already has its interrupted status set upon entry
   * to this method, or the thread is interrupted while waiting, then an
   * {@link InterruptedException} is thrown and the current threads interrupted
   * status is cleared.
   * <p>
   * If the specified waiting time elapses, then the method returns {@code false}.
   *
   * @param timeout The timeout delimiting the maximum time to wait for the
   * task to complete. Timeouts with zero or negative values will cause the
   * method to return immediately.
   * @return {@code true} if the task represented by this completion
   * completed within the specified waiting time, or was already complete upon
   * entry to this method; or {@code false} if the specified Timeout
   * elapsed before the task could finish.
   * @throws InterruptedException if the current thread is interrupted while
   * waiting.
   * @throws IllegalArgumentException if the provided {@code timeout} parameter is
   * {@code null}.
   */
  boolean await(Timeout timeout) throws InterruptedException;

  /**
   * Immediately return whether the completion has completed or not.
   *
   * @return {@code true} if this completion has completed, otherwise {@code false}.
   */
  boolean isCompleted();
}
