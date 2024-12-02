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
/*
 * Parts of this code was copied from the JCTools MpmcUnboundedXaddArrayQueue,
 * authored by Francesco Nigro (https://github.com/franz1981)
 * and Nitsan Wakart (https://github.com/nitsanw)
 */
package stormpot.internal;

import java.lang.invoke.MethodHandles;
import java.lang.invoke.VarHandle;
import java.util.ArrayDeque;
import java.util.Arrays;
import java.util.Objects;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TransferQueue;
import java.util.concurrent.locks.LockSupport;
import java.util.function.Predicate;

public class MpmcChunkedBlockingQueue<T> {
  private static final Thread TOMBSTONE = Thread.ofVirtual().unstarted(() -> {
  });
  private final ChunkQueue<T> messages;
  private final ChunkQueue<Thread> waiters;

  public MpmcChunkedBlockingQueue() {
    messages = new ChunkQueue<>(256, 32);
    waiters = new ChunkQueue<>(32, 32);
  }

  public void offer(T obj) {
    messages.offer(obj);
    if (!waiters.isEmpty()) {
      Thread th;
      while ((th = waiters.poll()) != null) {
        if (th != TOMBSTONE) {
          LockSupport.unpark(th);
          break;
        }
      }
    }
  }

  public T poll() {
    return messages.poll();
  }

  public T poll(long timeout, TimeUnit unit) throws InterruptedException {
    T obj = messages.poll();
    if (obj != null) {
      return obj;
    }
    Thread thread = Thread.currentThread();
    long stamp = waiters.offer(thread);
    long waitTime = unit.toNanos(timeout);
    long start = System.nanoTime();
    do {
      LockSupport.parkNanos(this, waitTime);
      obj = messages.poll();
      if (obj != null) {
        waiters.casEntry(stamp, thread, TOMBSTONE);
        return obj;
      }
      if (Thread.interrupted()) {
        waiters.casEntry(stamp, thread, TOMBSTONE);
        throw new InterruptedException();
      }
      if (!waiters.hasEntry(stamp, thread)) {
        stamp = waiters.offer(thread);
      }
      waitTime = unit.toNanos(timeout) - (System.nanoTime() - start);
    } while (waitTime > 0);
    waiters.casEntry(stamp, thread, TOMBSTONE);
    return null;
  }

  public T take() throws InterruptedException {
    T obj = messages.poll();
    if (obj != null) {
      return obj;
    }
    Thread thread = Thread.currentThread();
    long stamp = waiters.offer(thread);
    for (; ; ) {
      LockSupport.park(this);
      obj = messages.poll();
      if (obj != null) {
        waiters.casEntry(stamp, thread, TOMBSTONE);
        return obj;
      }
      if (Thread.interrupted()) {
        waiters.casEntry(stamp, thread, TOMBSTONE);
        throw new InterruptedException();
      }
      if (!waiters.hasEntry(stamp, thread)) {
        stamp = waiters.offer(thread);
      }
    }
  }

  /**
   * See {@link TransferQueue#hasWaitingConsumer()}
   *
   * @return {@code true} if there are waiting consumers
   */
  public boolean hasWaitingConsumer() {
    return waiters.count(obj -> obj != TOMBSTONE) > 0;
  }

  public boolean isEmpty() {
    return messages.isEmpty();
  }

  public long size() {
    return messages.size();
  }

  public long count(Predicate<T> predicate) {
    return messages.count(obj -> obj != TOMBSTONE && predicate.test(obj));
  }

  @SuppressWarnings("unused")
  private static class ChunkQueueP1 {
    private long p00;
    private long p01;
    private long p02;
    private long p03;
    private long p04;
    private long p05;
    private long p06;
    private long p07;
    private long p08;
    private long p09;
    private long p0A;
    private long p0B;
    private long p0C;
    private long p0D;
    private long p0E;
    private long p0F;
  }

  private static class ChunkQueueConsumerFields extends ChunkQueueP1 {
    @SuppressWarnings("unused")
    public volatile long consumerIndex;
    @SuppressWarnings("unused")
    public volatile Chunk consumerChunk;
  }

  @SuppressWarnings({"jol", "unused"})
  private static class ChunkQueueP2 extends ChunkQueueConsumerFields {
    private long p10;
    private long p11;
    private long p12;
    private long p13;
    private long p14;
    private long p15;
    private long p16;
    private long p17;
    private long p18;
    private long p19;
    private long p1A;
    private long p1B;
    private long p1C;
    private long p1D;
    private long p1E;
    private long p1F;
  }

  /**
   * A modified version of JCTools' MpmcUnboundedXaddArrayQueue.
   */
  @SuppressWarnings("jol")
  private static class ChunkQueue<E> extends ChunkQueueP2 {
    private static final VarHandle PRODUCER_INDEX;
    private static final VarHandle PRODUCER_CHUNK;
    private static final VarHandle PRODUCER_CHUNK_INDEX;
    private static final VarHandle CONSUMER_INDEX;
    private static final VarHandle CONSUMER_CHUNK;
    // it must be != Chunk.NOT_USED
    private static final long ROTATION = -2;

    static {
      MethodHandles.Lookup lookup = MethodHandles.lookup();
      try {
        PRODUCER_INDEX = lookup.findVarHandle(ChunkQueue.class, "producerIndex", long.class)
                .withInvokeExactBehavior();
        PRODUCER_CHUNK = lookup.findVarHandle(ChunkQueue.class, "producerChunk", Chunk.class)
                .withInvokeExactBehavior();
        PRODUCER_CHUNK_INDEX = lookup.findVarHandle(ChunkQueue.class, "producerChunkIndex", long.class)
                .withInvokeExactBehavior();
        CONSUMER_INDEX = lookup.findVarHandle(ChunkQueueConsumerFields.class, "consumerIndex", long.class)
                .withInvokeExactBehavior();
        CONSUMER_CHUNK = lookup.findVarHandle(ChunkQueueConsumerFields.class, "consumerChunk", Chunk.class)
                .withInvokeExactBehavior();
      } catch (Exception e) {
        throw new ExceptionInInitializerError(e);
      }
    }

    @SuppressWarnings("unused")
    private volatile long producerIndex;
    @SuppressWarnings("unused")
    private volatile Chunk producerChunk;
    @SuppressWarnings("unused")
    private volatile long producerChunkIndex;
    private final int chunkMask;
    private final int chunkShift;
    // XXX was SpscArrayQueue, using ArrayDeque might not be thread-safe?
    private final ArrayDeque<Chunk> freeChunksPool;

    /**
     * @param chunkSize       The buffer size to be used in each chunk of this queue
     * @param maxPooledChunks The maximum number of reused chunks kept around to avoid allocation,
     *                        chunks are pre-allocated
     */
    ChunkQueue(int chunkSize, int maxPooledChunks) {
      if (maxPooledChunks < 0) {
        throw new IllegalArgumentException("Expecting a positive maxPooledChunks, but got:" + maxPooledChunks);
      }
      this.chunkMask = chunkSize - 1;
      this.chunkShift = Integer.numberOfTrailingZeros(chunkSize);
      freeChunksPool = new ArrayDeque<>(maxPooledChunks);//new SpscArrayQueue<R>(maxPooledChunks);

      final Chunk first = newChunk(0, null, chunkSize, maxPooledChunks > 0);
      soProducerChunk(first);
      soProducerChunkIndex(0);
      soConsumerChunk(first);
      for (int i = 1; i < maxPooledChunks; i++) {
        // TODO avoid eagerly allocating chunks?
        freeChunksPool.offer(newChunk(Chunk.NOT_USED, null, chunkSize, true));
      }
    }

    public final long lvProducerIndex() {
      return producerIndex;
    }

    final long getAndIncrementProducerIndex() {
      return (long) PRODUCER_INDEX.getAndAdd(this, 1L);
    }

    final long lvProducerChunkIndex() {
      return producerChunkIndex;
    }

    final boolean casProducerChunkIndex(long expected, long value) {
      return (boolean) PRODUCER_CHUNK_INDEX.compareAndSet(this, expected, value);
    }

    final void soProducerChunkIndex(long value) {
      PRODUCER_CHUNK_INDEX.setRelease(this, value);
    }

    final Chunk lvProducerChunk() {
      return this.producerChunk;
    }

    final void soProducerChunk(Chunk chunk) {
      PRODUCER_CHUNK.setRelease(this, chunk);
    }

    public final long lvConsumerIndex() {
      return consumerIndex;
    }

    final boolean casConsumerIndex(long expect, long newValue) {
      return (boolean) CONSUMER_INDEX.compareAndSet((ChunkQueueConsumerFields) this, expect, newValue);
    }

    final Chunk lvConsumerChunk() {
      return this.consumerChunk;
    }

    final void soConsumerChunk(Chunk newValue) {
      CONSUMER_CHUNK.setRelease((ChunkQueueConsumerFields) this, newValue);
    }

    /**
     * We're here because currentChunk.index doesn't match the expectedChunkIndex.
     * To resolve we must now chase the linked chunks to the appropriate chunk.
     * More than one producer may end up racing to add or discover new chunks.
     *
     * @param initialChunk       the starting point chunk, which does not match the required chunk index
     * @param requiredChunkIndex the chunk index we need
     * @return the chunk matching the required index
     */
    final Chunk producerChunkForIndex(
            final Chunk initialChunk,
            final long requiredChunkIndex) {
      Chunk currentChunk = initialChunk;
      long jumpBackward;
      while (true) {
        if (currentChunk == null) {
          currentChunk = lvProducerChunk();
        }
        final long currentChunkIndex = currentChunk.lvIndex();
        assert currentChunkIndex != Chunk.NOT_USED;
        // if the required chunk index is less than the current chunk index then we need to walk the linked list of
        // chunks back to the required index
        jumpBackward = currentChunkIndex - requiredChunkIndex;
        if (jumpBackward >= 0) {
          break;
        }
        // try validate against the last producer chunk index
        if (lvProducerChunkIndex() == currentChunkIndex) {
          currentChunk = appendNextChunks(currentChunk, currentChunkIndex, -jumpBackward);
        } else {
          currentChunk = null;
        }
      }
      for (long i = 0; i < jumpBackward; i++) {
        // prev cannot be null, because the consumer cannot null it without consuming the element for which we are
        // trying to get the chunk.
        currentChunk = currentChunk.lvPrev();
        assert currentChunk != null;
      }
      assert currentChunk.lvIndex() == requiredChunkIndex;
      return currentChunk;
    }

    final Chunk appendNextChunks(
            Chunk currentChunk,
            long currentChunkIndex,
            long chunksToAppend) {
      assert currentChunkIndex != Chunk.NOT_USED;
      // prevent other concurrent attempts on appendNextChunk
      if (!casProducerChunkIndex(currentChunkIndex, ROTATION)) {
        return null;
      }
      /* LOCKED FOR APPEND */
      {
        // it is valid for the currentChunk to be consumed while appending is in flight, but it's not valid for the
        // current chunk ordering to change otherwise.
        assert currentChunkIndex == currentChunk.lvIndex();

        for (long i = 1; i <= chunksToAppend; i++) {
          Chunk newChunk = newOrPooledChunk(currentChunk, currentChunkIndex + i);
          soProducerChunk(newChunk);
          //link the next chunk only when finished
          currentChunk.soNext(newChunk);
          currentChunk = newChunk;
        }

        // release appending
        soProducerChunkIndex(currentChunkIndex + chunksToAppend);
      }
      /* UNLOCKED FOR APPEND */
      return currentChunk;
    }

    private Chunk newOrPooledChunk(Chunk prevChunk, long nextChunkIndex) {
      Chunk newChunk = freeChunksPool.poll();
      if (newChunk != null) {
        // single-writer: prevChunk::index == nextChunkIndex is protecting it
        assert newChunk.lvIndex() < prevChunk.lvIndex();
        newChunk.soPrev(prevChunk);
        // index set is releasing prev, allowing other pending offers to continue
        newChunk.soIndex(nextChunkIndex);
      } else {
        newChunk = newChunk(nextChunkIndex, prevChunk, chunkMask + 1, false);
      }
      return newChunk;
    }

    /**
     * Does not null out the first element of `next`, callers must do that
     */
    final void moveToNextConsumerChunk(Chunk cChunk, Chunk next) {
      // avoid GC nepotism
      cChunk.soNext(null);
      next.soPrev(null);
      // no need to cChunk.soIndex(Chunk.NOT_USED)
      if (cChunk.isPooled()) {
        final boolean pooled = freeChunksPool.offer(cChunk);
        assert pooled;
      }
      this.soConsumerChunk(next);
      // MC case:
      // from now on the code is not single-threaded anymore and
      // other consumers can move forward consumerIndex
    }

    public long size() {
      /*
       * It is possible for a thread to be interrupted or reschedule between the reads of the producer and
       * consumer indices. It is also for the indices to be updated in a `weakly` visible way. It follows that
       * the size value needs to be sanitized to match a valid range.
       */
      long after = lvConsumerIndex();
      long size;
      for (; ; ) {
        final long before = after;
        // pIndex read is "sandwiched" between 2 cIndex reads
        final long currentProducerIndex = lvProducerIndex();
        after = lvConsumerIndex();
        if (before == after) {
          size = (currentProducerIndex - after);
          break;
        }
      }
      return Math.max(0, size);
    }

    public boolean isEmpty() {
      // Order matters!
      // Loading consumer before producer allows for producer increments after consumer index is read.
      // This ensures this method is conservative in it's estimate. Note that as this is an MPMC there is
      // nothing we can do to make this an exact method.
      return lvConsumerIndex() >= lvProducerIndex();
    }

    final Chunk newChunk(long index, Chunk prev, int chunkSize, boolean pooled) {
      return new Chunk(index, prev, chunkSize, pooled);
    }

    public long offer(E e) {
      Objects.requireNonNull(e, "element");
      final int chunkMask = this.chunkMask;
      final int chunkShift = this.chunkShift;

      final long pIndex = getAndIncrementProducerIndex();

      final int piChunkOffset = (int) (pIndex & chunkMask);
      final long piChunkIndex = pIndex >> chunkShift;

      Chunk pChunk = lvProducerChunk();
      if (pChunk.lvIndex() != piChunkIndex) {
        // Other producers may have advanced the producer chunk as we claimed a slot in a prev chunk, or we may have
        // now stepped into a brand new chunk which needs appending.
        pChunk = producerChunkForIndex(pChunk, piChunkIndex);
      }

      final boolean isPooled = pChunk.isPooled();

      if (isPooled) {
        // wait any previous consumer to finish its job
        pChunk.spinForElement(piChunkOffset, true);
      }
      pChunk.soElement(piChunkOffset, e);
      if (isPooled) {
        pChunk.soSequence(piChunkOffset, piChunkIndex);
      }
      return pIndex;
    }

    public boolean hasEntry(long index, E value) {
      Objects.requireNonNull(value, "value");
      final int chunkOffset = (int) (index & chunkMask);
      final long chunkIndex = index >> chunkShift;

      Chunk chunk = lvConsumerChunk();
      long currIndex = -1;
      while (chunk != null && (currIndex = chunk.lvIndex()) < chunkIndex) {
        chunk = chunk.lvNext();
      }
      if (chunk == null || currIndex != chunkIndex) {
        return false;
      }
      return chunk.lvElement(chunkOffset) == value;
    }

    public void casEntry(long index, E value, E update) {
      Objects.requireNonNull(value, "value");
      Objects.requireNonNull(update, "update");
      final int chunkOffset = (int) (index & chunkMask);
      final long chunkIndex = index >> chunkShift;

      Chunk chunk = lvConsumerChunk();
      long currIndex = -1;
      while (chunk != null && (currIndex = chunk.lvIndex()) < chunkIndex) {
        chunk = chunk.lvNext();
      }
      if (chunk == null || currIndex != chunkIndex) {
        return;
      }
      chunk.casElement(chunkOffset, value, update);
    }

    @SuppressWarnings("unchecked")
    public long count(Predicate<E> predicate) {
      long count = 0;
      Chunk chunk = lvConsumerChunk();
      while (chunk != null) {
        for (Object obj : chunk.buffer) {
          if (obj != null && predicate.test((E) obj)) {
            count++;
          }
        }
        chunk = chunk.lvNext();
      }
      return count;
    }

    @SuppressWarnings("unchecked")
    public E poll() {
      final int chunkMask = this.chunkMask;
      final int chunkShift = this.chunkShift;
      long cIndex;
      Chunk cChunk;
      int ciChunkOffset;
      boolean isFirstElementOfNewChunk;
      boolean pooled = false;
      Object e = null;
      Chunk next = null;
      long pIndex = -1; // start with bogus value, hope we don't need it
      long ciChunkIndex;
      while (true) {
        isFirstElementOfNewChunk = false;
        cIndex = this.lvConsumerIndex();
        // chunk is in sync with the index, and is safe to mutate after CAS of index (because we pre-verify it
        // matched the indicate ciChunkIndex)
        cChunk = this.lvConsumerChunk();

        ciChunkOffset = (int) (cIndex & chunkMask);
        ciChunkIndex = cIndex >> chunkShift;

        final long ccChunkIndex = cChunk.lvIndex();
        if (ciChunkOffset == 0 && cIndex != 0) {
          if (ciChunkIndex - ccChunkIndex != 1) {
            continue;
          }
          isFirstElementOfNewChunk = true;
          next = cChunk.lvNext();
          // next could have been modified by another racing consumer, but:
          // - if null: it still needs to check q empty + casConsumerIndex
          // - if !null: it will fail on casConsumerIndex
          if (next == null) {
            if (cIndex >= pIndex && // test against cached pIndex
                    cIndex == (pIndex = lvProducerIndex())) { // update pIndex if we must
              // strict empty check, this ensures [Queue.poll() == null iff isEmpty()]
              return null;
            }
            // we will go ahead with the CAS and have the winning consumer spin for the next buffer
          }
          // not empty: can attempt the cas (and transition to next chunk if successful)
          if (casConsumerIndex(cIndex, cIndex + 1)) {
            break;
          }
          continue;
        }
        if (ccChunkIndex > ciChunkIndex) {
          //stale view of the world
          continue;
        }
        // mid chunk elements
        pooled = cChunk.isPooled();
        if (ccChunkIndex == ciChunkIndex) {
          if (pooled) {
            // Pooled chunks need a stronger guarantee than just element null checking in case of a stale view
            // on a reused entry where a racing consumer has grabbed the slot but not yet null-ed it out and a
            // producer has not yet set it to the new value.
            final long sequence = cChunk.lvSequence(ciChunkOffset);
            if (sequence == ciChunkIndex) {
              if (casConsumerIndex(cIndex, cIndex + 1)) {
                break;
              }
              continue;
            }
            if (sequence > ciChunkIndex) {
              //stale view of the world
              continue;
            }
            // sequence < ciChunkIndex: element yet to be set?
          } else {
            e = cChunk.lvElement(ciChunkOffset);
            if (e != null) {
              if (casConsumerIndex(cIndex, cIndex + 1)) {
                break;
              }
              continue;
            }
            // e == null: element yet to be set?
          }
        }
        // ccChunkIndex < ciChunkIndex || e == null || sequence < ciChunkIndex:
        if (cIndex >= pIndex && // test against cached pIndex
                cIndex == (pIndex = lvProducerIndex())) { // update pIndex if we must
          // strict empty check, this ensures [Queue.poll() == null iff isEmpty()]
          return null;
        }
      }

      // if we are the isFirstElementOfNewChunk we need to get the consumer chunk
      if (isFirstElementOfNewChunk) {
        e = switchToNextConsumerChunkAndPoll(cChunk, next, ciChunkIndex);
      } else {
        if (pooled) {
          e = cChunk.lvElement(ciChunkOffset);
        }
        assert !cChunk.isPooled() || (cChunk.lvSequence(ciChunkOffset) == ciChunkIndex);

        cChunk.soElement(ciChunkOffset, null);
      }
      return (E) e;
    }

    private Object switchToNextConsumerChunkAndPoll(
            Chunk cChunk,
            Chunk next,
            long expectedChunkIndex) {
      if (next == null) {
        final long ccChunkIndex = expectedChunkIndex - 1;
        assert cChunk.lvIndex() == ccChunkIndex;
        if (lvProducerChunkIndex() == ccChunkIndex) {
          // no need to help too much here or the consumer latency will be hurt
          next = appendNextChunks(cChunk, ccChunkIndex, 1);
        }
      }
      while (next == null) {
        next = cChunk.lvNext();
      }
      // we can freely spin awaiting producer, because we are the only one in charge to
      // rotate the consumer buffer and use next
      final Object e = next.spinForElement(0, false);

      final boolean pooled = next.isPooled();
      if (pooled) {
        next.spinForSequence(0, expectedChunkIndex);
      }

      next.soElement(0, null);
      moveToNextConsumerChunk(cChunk, next);
      return e;
    }
  }

  private static final class Chunk {
    public final static int NOT_USED = -1;

    private static final VarHandle PREV;
    private static final VarHandle NEXT;
    private static final VarHandle INDEX;
    private static final VarHandle BUFFER;
    private static final VarHandle SEQUENCE;

    static {
      MethodHandles.Lookup lookup = MethodHandles.lookup();
      try {
        PREV = lookup.findVarHandle(Chunk.class, "prev", Chunk.class)
                .withInvokeExactBehavior();
        NEXT = lookup.findVarHandle(Chunk.class, "next", Chunk.class)
                .withInvokeExactBehavior();
        INDEX = lookup.findVarHandle(Chunk.class, "index", long.class)
                .withInvokeExactBehavior();
        BUFFER = MethodHandles.arrayElementVarHandle(Object[].class)
                .withInvokeExactBehavior();
        SEQUENCE = MethodHandles.arrayElementVarHandle(long[].class)
                .withInvokeExactBehavior();
      } catch (Exception e) {
        throw new ExceptionInInitializerError(e);
      }
    }

    private final boolean pooled;
    private final Object[] buffer;
    private final long[] sequence;

    @SuppressWarnings("unused")
    private volatile Chunk prev;
    @SuppressWarnings("unused")
    private volatile long index;
    @SuppressWarnings("unused")
    private volatile Chunk next;

    Chunk(long index, Chunk prev, int size, boolean pooled) {
      buffer = new Object[size];
      // next is null
      soPrev(prev);
      spIndex(index);
      this.pooled = pooled;
      if (pooled) {
        sequence = new long[size];
        Arrays.fill(sequence, Chunk.NOT_USED);
      } else {
        sequence = null;
      }
    }

    public boolean isPooled() {
      return pooled;
    }

    public long lvIndex() {
      return index;
    }

    public void soIndex(long index) {
      INDEX.setRelease(this, index);
    }

    void spIndex(long index) {
      INDEX.set(this, index);
    }

    public Chunk lvNext() {
      return next;
    }

    public void soNext(Chunk value) {
      NEXT.setRelease(this, value);
    }

    public Chunk lvPrev() {
      return prev;
    }

    public void soPrev(Chunk value) {
      PREV.setRelease(this, value);
    }

    public void soElement(int index, Object e) {
      BUFFER.setRelease(buffer, index, e);
    }

    public Object lvElement(int index) {
      return (Object) BUFFER.getVolatile(buffer, index);
    }

    public Object spinForElement(int index, boolean isNull) {
      Object[] buffer = this.buffer;
      Object e = (Object) BUFFER.getVolatile(buffer, index);
      while (isNull != (e == null)) {
        Thread.onSpinWait();
        e = (Object) BUFFER.getVolatile(buffer, index);
      }
      return e;
    }

    public void soSequence(int index, long e) {
      assert isPooled();
      SEQUENCE.setRelease(sequence, index, e);
    }

    public long lvSequence(int index) {
      assert isPooled();
      return (long) SEQUENCE.getVolatile(sequence, index);
    }

    public void spinForSequence(int index, long e) {
      assert isPooled();
      final long[] sequence = this.sequence;
      for (; ; ) {
        long value = (long) SEQUENCE.getVolatile(sequence, index);
        if (value == e) {
          break;
        }
        Thread.onSpinWait();
      }
    }

    public boolean casElement(int index, Object value, Object update) {
      return (boolean) BUFFER.compareAndSet(buffer, index, value, update);
    }
  }
}
