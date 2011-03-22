Stormpot
========

After having built NanoPool, I ended up needing a generic object pool
implementation. Stormpot is that object pool.

In addition to being generic, the goal for Stormpot is to also be a
faster pool than NanoPool. Unlike NanoPool, Stormpot uses a blocking
implementation. And also unlike NanoPool, Stormpot has internal threads
that maintain the pool, and make sure that objects don't go stale.

