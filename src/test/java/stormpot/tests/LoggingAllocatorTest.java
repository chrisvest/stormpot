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
import org.junit.jupiter.api.Test;
import stormpot.Allocator;
import stormpot.LoggingAllocator;
import stormpot.Slot;
import testkits.DelegateAllocator;
import testkits.DelegateReallocator;
import testkits.GenericPoolable;
import testkits.NullSlot;

import java.util.concurrent.BlockingQueue;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static testkits.AlloKit.$new;
import static testkits.AlloKit.$throw;
import static testkits.AlloKit.alloc;
import static testkits.AlloKit.allocator;
import static testkits.AlloKit.dealloc;
import static testkits.AlloKit.realloc;
import static testkits.AlloKit.reallocator;

class LoggingAllocatorTest {
  private NullSlot slot;
  private BlockingQueue<LogEvent> queue;
  private Exception exception;

  @BeforeEach
  void setUp() {
    slot = new NullSlot();
    queue = new LinkedBlockingQueue<>();
    exception = new Exception("boom");
  }

  @Test
  void allocateMustLogFailures() throws Exception {
    LoggingAllocator<GenericPoolable> allocator = new QueueLoggingAllocator(
            allocator(alloc($throw(exception))), queue);

    Exception thrown = assertThrows(Exception.class, () -> allocator.allocate(slot));

    assertSame(exception, thrown);
    assertLogged(queue, LoggingAllocator.ALLOCATION_FAILED, exception);
  }

  @Test
  void allocateAsyncMustLogFailedStages() throws Exception {
    LoggingAllocator<GenericPoolable> allocator = new QueueLoggingAllocator(
            allocator(alloc($throw(exception))), queue);

    ExecutionException execException = assertThrows(ExecutionException.class,
            () -> allocator.allocateAsync(slot).toCompletableFuture().get());
    assertThat(execException).hasCause(exception);

    assertLogged(queue, LoggingAllocator.ASYNC_ALLOCATION_FAILED, exception);
  }

  @Test
  void allocateAsyncMustLogWhenNullStageIsReturned() throws Exception {
    LoggingAllocator<GenericPoolable> allocator = new QueueLoggingAllocator(new DelegateAllocator<>(allocator()) {
      @Override
      public CompletionStage<GenericPoolable> allocateAsync(Slot slot) {
        return null;
      }
    }, queue);

    CompletionStage<GenericPoolable> stage = allocator.allocateAsync(slot);

    assertNull(stage);
    assertLogged(queue, LoggingAllocator.ASYNC_ALLOCATION_RETURNED_NULL, null);
  }

  @Test
  void deallocateMustLogFailures() throws Exception {
    LoggingAllocator<GenericPoolable> allocator = new QueueLoggingAllocator(
            allocator(dealloc($throw(exception))), queue);

    GenericPoolable obj = allocator.allocate(slot);
    Exception thrown = assertThrows(Exception.class, () -> allocator.deallocate(obj));

    assertSame(exception, thrown);
    assertLogged(queue, LoggingAllocator.DEALLOCATION_FAILED, exception);
  }

  @Test
  void deallocateAsyncMustLogFailedStages() throws Exception {
    LoggingAllocator<GenericPoolable> allocator = new QueueLoggingAllocator(
            allocator(dealloc($throw(exception))), queue);

    allocator.deallocateAsync(allocator.allocate(slot));

    assertLogged(queue, LoggingAllocator.ASYNC_DEALLOCATION_FAILED, exception);
  }

  @Test
  void deallocateAsyncMustLogWhenNullStageIsReturned() throws Exception {
    LoggingAllocator<GenericPoolable> allocator = new QueueLoggingAllocator(new DelegateAllocator<>(allocator()) {
      @Override
      public CompletionStage<Void> deallocateAsync(GenericPoolable poolable) {
        return null;
      }
    }, queue);

    CompletionStage<Void> stage = allocator.deallocateAsync(allocator.allocate(slot));

    assertNull(stage);
    assertLogged(queue, LoggingAllocator.ASYNC_DEALLOCATION_RETURNED_NULL, null);
  }

  @Test
  void reallocateMustLogPoolExceptionFromReallocator() throws Exception {
    LoggingAllocator<GenericPoolable> allocator = new QueueLoggingAllocator(
            reallocator(realloc($throw(exception))), queue);

    GenericPoolable obj = allocator.allocate(slot);
    Exception thrown = assertThrows(Exception.class,
        () -> allocator.reallocate(slot, obj));

    assertSame(exception, thrown);
    assertLogged(queue, LoggingAllocator.REALLOCATION_FAILED, exception);
  }

  @Test
  void reallocateMustLogPoolExceptionFromFallbackPath() throws Exception {
    LoggingAllocator<GenericPoolable> allocator = new QueueLoggingAllocator(
            allocator(alloc($new, $throw(exception))), queue);

    GenericPoolable obj = allocator.allocate(slot);
    Exception thrown = assertThrows(Exception.class,
        () -> allocator.reallocate(slot, obj));

    assertSame(exception, thrown);
    assertLogged(queue, LoggingAllocator.REALLOCATION_FAILED, exception);
  }

  @Test
  void reallocateAsyncMustLogFailedStages() throws Exception {
    LoggingAllocator<GenericPoolable> allocator = new QueueLoggingAllocator(
            new DelegateReallocator<>(reallocator(realloc($throw(exception)))), queue);

    GenericPoolable obj = allocator.allocate(slot);
    CompletionStage<GenericPoolable> stage = allocator.reallocateAsync(slot, obj);
    ExecutionException execException = assertThrows(ExecutionException.class, () -> stage.toCompletableFuture().get());

    assertThat(execException).hasCause(exception);
    assertLogged(queue, LoggingAllocator.ASYNC_REALLOCATION_FAILED, exception);
  }

  @Test
  void reallocateAsyncMustLogWhenNullStageIsReturned() throws Exception {
    LoggingAllocator<GenericPoolable> allocator = new QueueLoggingAllocator(new DelegateReallocator<>(reallocator()) {
      @Override
      public CompletionStage<GenericPoolable> reallocateAsync(Slot slot, GenericPoolable poolable) {
        return null;
      }
    }, queue);

    GenericPoolable obj = allocator.allocate(slot);
    CompletionStage<GenericPoolable> stage = allocator.reallocateAsync(slot, obj);

    assertNull(stage);
    assertLogged(queue, LoggingAllocator.ASYNC_REALLOCATION_RETURNED_NULL, null);
  }

  private static void assertLogged(
      BlockingQueue<LogEvent> queue, String expectedMessage, Throwable expectedThrowable) throws Exception {
    LogEvent event = queue.poll(1, TimeUnit.SECONDS);
    assertNotNull(event, "Expected a log event");
    assertSame(expectedThrowable, event.throwable);
    assertEquals(expectedMessage, event.message);
  }

  private record LogEvent(String message, Throwable throwable) {
  }

  private static final class QueueLoggingAllocator extends LoggingAllocator<GenericPoolable> {
    private final BlockingQueue<LogEvent> queue;

    private QueueLoggingAllocator(Allocator<GenericPoolable> allocator, BlockingQueue<LogEvent> queue) {
      super(allocator);
      this.queue = queue;
    }

    @Override
    protected void logMessage(String message, Throwable throwable) {
      queue.add(new LogEvent(message, throwable));
    }
  }
}
