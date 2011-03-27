Stormpot
========

After having built NanoPool, I ended up needing a generic object pool
implementation. Stormpot is that object pool.

In addition to being generic, the goal for Stormpot is to also be a
faster pool than NanoPool. Unlike NanoPool, Stormpot uses a blocking
implementation. And also unlike NanoPool, Stormpot has internal threads
that maintain the pool, and make sure that objects don't go stale.

Design
------

Pool: a concurrent collection of segments.

Segments: a collection of poolables. The data structure is guarded by
a lock, to reduce the number of memory fences needed for common
operations on the data structure.

To claim an object, a segment is chosen based on the starting point
algorithm. The segment is locked and the thread tries to claim an object
in the data structure. If the segment has been depleted, the thread
unlocks it and tries the next segment in the pool.

Each segment has their own lock, so the lock in the pool is striped like
this to reduce contention. If each segment only has one object, then
there will be lock-unlock operations for every object examined. So a
segment should have more than one object, to increase the chance of the
lock-unlock operation not being in vain.

The starting point algorithm is responsible for choosing which segment
a thread should examine first. Ideally, we want a thread to always start
with the same segment, to take advantage of biased locking in lowly
contended situations. This would require getting thread specific
information at the beginning of every claim. A number of possible
options exist:

 * The identity hashcode of the Thread object.
 * The current threadId.
 * A ThreadLocal primed from a counter.

If it turns out that these methods are all too expensive, then we might
be better off with a counter that distributes the incomming requests in
a round-robin manner. The counter may be atomic, to avoid sending two
threads in a row to the same segment as they are likely going to contend
for the segment lock. Or the counter can be racy and permit two threads
to sometimes get the same count. A racy counter would by itself put less
strain on the memory bus, but whether or not this outweighs the cost of
threads being likely to contend on segment locks is not known. This
needs to be tested and benchmarked.

