# Stormpot

> [!IMPORTANT]
> Development has moved to Codeberg: https://codeberg.org/chrisvest/stormpot
> 
> Report bugs and feature requests there.

Stormpot is an object pooling library for Java.
Use it to recycle objects that are expensive to create.
The library will take care of creating and destroying your objects in the
background.

Stormpot is very mature, is used in production, and has done hundreds of
trillions[^1] claim-release cycles in testing.
It is faster and scales better than any competing pool.

 * Home page: http://chrisvest.github.io/stormpot/
 * Source code: https://codeberg.org/chrisvest/stormpot
 * API docs: http://chrisvest.github.io/stormpot/site/apidocs/index.html
 * License: [The Apache Software License 2.0](http://www.apache.org/licenses/LICENSE-2.0.html)
 * Simple versioning: X.Y where Y++ is backwards compatible, and X++ is backwards incompatible.
 * Human made ❤️🏳️‍🌈🏳️‍⚧️✊🏾☮️

## Why choose Stormpot?

There are a number of options out there, when it comes to object pools on the
JVM. Stormpot has been carefully designed for high performance, and robust
operation. Some of the things that set Stormpot apart include:

 * Business friendly [Apache 2 license](http://www.apache.org/licenses/LICENSE-2.0.html).
 * Very high [test coverage](http://chrisvest.github.io/stormpot/site/jacoco/index.html).
 * The [highest throughput and lowest latency](https://medium.com/@chrisvest/released-stormpot-2-1-c31509142757) in its class.
   (since 2.1)
 * [Automatic recovery](https://medium.com/@chrisvest/released-stormpot-2-2-ccd1e8639f07) from sporadic backend (Allocator) failures.
   (since 2.2)
 * Precise object leak detection with virtually no overhead.
   (since 2.3)
 * Optional background object expiration checking.
   (since 2.3)
 * Explicit object expiration.
   (since 2.4)
 * Gradual back-off for prolonged allocation failures.
   (since 3.0)
 * Support for Java Platform Module system.
   (since 3.0)
 * Support for a directly-allocating thread-less mode, via `Pool.of(...)`.
   (since 3.0)
 * Convenient lambda-based API.
   (since 3.0)
 * Control over the thread-local caching mechanics, via ``PoolTap``s.
   (since 3.0)
 * Support for operating without a background thread, via `Pool.fromInline()`.
   (since 3.1)
 * Support for configuring zero-sized (dormant) pools.
   (since 3.1)
 * Support for virtual threads.
   (since 4.0)
 * Support for more than 2 billion objects in a pool.
   (since 4.0)
 * Support for changing the `Allocator` after the pool has been created.
   (since 4.0)
* Support for allocating/deallocating multiple objects in parallel.
   (since 4.2)
 * And other features that makes for a smooth runtime behavior.

> [!NOTE]
> Stormpot is an _object pool_; a homogeneous collection of objects, where it does not matter which particular instance is returned from `claim` since the objects are all similar.
> If your objects instead are heterogeneous, with different attributes and identified by a _key_, then what you need is a _object cache_.
> We recommend [Caffeine](https://github.com/ben-manes/caffeine) for object caching.

## Installing

Stormpot 4.0 only depends on Java 21 or newer.
If you need to use Java 11 or newer, use Stormpot 3.2.
Add it as a Maven dependency to your projects:


```xml
<dependency>
  <groupId>com.github.chrisvest</groupId>
  <artifactId>stormpot</artifactId>
  <version>4.2</version>
</dependency>
```

You can also build the latest snapshot from source with `mvn clean install`.
Note that Stormpot 4.x requires Java 21 or newer.

## Breaking Changes Coming in 5.0

Version 5.0 is in development and will have the following breaking changes:

* Java 25 will be the new minimum required version.
* The default expiration changes from "8 to 10 minutes" to "never".
 This can be reverted by explicitly configuring the pool with an expiration:
 `PoolBuilder.setExpiration(Expiration.after(8, 10, TimeUnit.MINUTES))`.

## Getting Started

Stormpot needs 3 things before it can pool objects for you:

* A http://chrisvest.github.io/stormpot/site/apidocs/stormpot/stormpot/Poolable.html[Poolable] type of objects it can pool.
  You have to implement this yourself.
* An http://chrisvest.github.io/stormpot/site/apidocs/stormpot/stormpot/Allocator.html[Allocator] to allocate and deallocate the Poolable objects.
  You have to implement this yourself.
* And a place where it all comes together:

```java
MyAllocator allocator = new MyAllocator();
Pool<MyPoolable> pool = Pool.from(allocator).build();
Timeout timeout = new Timeout(1, TimeUnit.SECONDS);

MyPoolable object = pool.claim(timeout);
try {
  // Do stuff with 'object'.
  // Note: 'claim' returns 'null' if it times out.
} finally {
  if (object != null) {
    object.release();
  }
}
```

[^1]: Fermi estimate.
