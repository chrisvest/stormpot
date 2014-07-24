/*
 * Copyright (C) 2011-2014 Chris Vest (mr.chrisvest@gmail.com)
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *         http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

/**
 * = Stormpot
 * :toc:
 *
 * Stormpot is a generic, thread-safe and fast object pooling library.
 *
 * The object pools themselves implement the {@link stormpot.Pool} interface,
 * or one or both of the {@link stormpot.LifecycledPool} or
 * {@link stormpot.ResizablePool} interfaces, or even the
 * {@link stormpot.LifecycledResizablePool} interface. The things you actually
 * want to pool must all implement the {@link stormpot.Poolable} interface, and
 * you must also provide an implementation of the {@link stormpot.Allocator}
 * interface as a factory to create your pooled objects.
 *
 * toc::[]
 *
 * == Why is it called "Stormpot"?
 *
 * A pot is a container of things (like gold, for instance) that you can put stuff
 * into and get stuff out of. The storm part refers to the proverbial storm in a
 * glass of water or teacup (or tempest in a teapot, depending on where you are
 * from) and is a reference to the size of the library and what it does. A storm
 * is also a wind that moves really fast, and that is a reference to the goal of
 * having a fast and scalable implementation. That, and "Windy Kettle" is two
 * words and sound silly.
 *
 * == Introduction
 *
 * Stormpot is an object pooling library for Java. It consists of an API, and
 * a number of implementations of that API. As such, Stormpot solves pretty
 * much the same problem as http://commons.apache.org/pool/[Apache Commons-Pool].
 * The main differences are these:
 *
 * * Stormpot has a slightly more invasive API.
 * * Stormpot depends on Java6 or newer, whereas Commons-Pool can work with
 *   Java 1.3 and up.
 * * Stormpot pools prefer to allocate objects in a "back-ground" thread,
 *   whereas Commons-Pool prefer that objects are explicitly added to the
 *   pools.
 * * Stormpot only has one type of pool, with extension interfaces, whereas
 *   Commons-Pool has two types, where the implementations may expose additional
 *   implementation specific APIs.
 * * Commons-Pool has support for keyed pools, akin to caches, whereas
 *   Stormpot does not.
 * * The Stormpot API is slanted towards high through-put. The Commons-Pool
 *   API is slanted towards a rich feature set.
 *
 * Apart from the differences, there are also a number of similarities:
 *
 * * Both have high test coverage.
 * * Both have small code-bases.
 * * Both are thread-safe.
 * * Both have no dependencies on anything other than Java itself.
 * * Both are licensed under the Apache 2.0 license.
 * * Both have thorough API documentation.
 *
 * So those are the things to keep in mind, when deciding on which pool to use.
 *
 * == Simplest Possible Usage
 *
 * Stormpot has an invasive API which means that there is a minimum of things your
 * code needs to do to use it. However, the requirements are quite benign, as this
 * section is all about showing you.
 *
 * The objects that you store in a Stormpot pool needs to implement the
 * {@link stormpot.Poolable} interface. The absolute minimum amount of code
 * required for its implementation is this:
 *
 * [source,java]
 * --
 * include::docs/MyPoolable.java[tag=mypoolable]
 * --
 *
 * The object in essence just needs to keep its +Slot+ instance around, and give
 * itself as a parameter to the {@link stormpot.Slot#release(Poolable)} method.
 *
 * Now that we have a class of objects to pool, we need some way to tell Stormpot
 * how to create them. We do this by implementing the {@link stormpot.Allocator}
 * interface:
 *
 * [source,java]
 * --
 * include::docs/MyAllocator.java[tag=myallocator]
 * --
 *
 * That's it. Given a slot, create a +MyPoolable+. Or given a +MyPoolable+,
 * deallocate it. And that is actually all the parts we need to start using
 * Stormpot. All that is left is a little bit of configuration:
 *
 * [source,java]
 * --
 * include::docs/MyApp.java[tag=usageMyApp]
 * --
 *
 * Create a +Config+ object and set the allocator, then create a pool with
 * the configuration and off we go!
 *
 * The blocking methods {@link stormpot.Pool#claim(Timeout)} and
 * {@link stormpot.Completion#await(Timeout)} both take {@link stormpot.Timeout}
 * objects as arguments. These are thread-safe, and can easily be put in
 * +static final+ constants. Note that +claim+ returns +null+ if the timeout
 * elapses before an object can be claimed. Also, using +try-finally+ is a great
 * way to make sure that you don't forget to release the objects back into the
 * pool. Stormpot does no leak detection, so if you loose the reference to an
 * object that hasn't been released back into the pool, it will not come back on
 * its own. Leaked objects can also cause problems with shutting the pool down,
 * because the shut down procedure does not complete until all allocated objects
 * are deallocated. So if you have leaked objects, shutting the pool down will
 * never complete normally. You can still just halt the JVM, though.
 *
 * == Tutorial
 *
 * As mentioned in the sections above, Stormpot has an invasive API. This means
 * that there are things you have to do, before you can make use of its pooling
 * capabilities. In this section, we will take a look at these things, and see
 * what it takes to implement pooling of some DAOs. This is just an example, but
 * it will touch on all of the basics you need to know, in order to get started
 * with Stormpot.
 *
 * We are going to pool Data Access Objects, or DAOs, and each of these will
 * work with a database {@link java.sql.Connection connection} that comes from
 * a {@link javax.sql.DataSource}. So let us start off by importing those:
 *
 * [source,java]
 * --
 * import java.sql.Connection;
 * import java.sql.SQLException;
 *
 * import javax.sql.DataSource;
 * --
 *
 * We are also going to need most of the Stormpot API. We are going to need
 * {@link stormpot.Config} for our pool configuration;
 * {@link stormpot.LifecycledPool} is the pool interface we will code against,
 * because we want our code to have a clean shut-down path; some methods take a
 * {@link stormpot.Timeout} object as a parameter so we'll that as well;
 * {@link stormpot.Allocator} and {@link stormpot.Poolable} are interfaces we
 * are going to have to implement in our own code, and we will be needing the
 * {@link stormpot.Slot} interface to do that; and finally we are going to
 * need a concrete pool implementation from the library: the
 * {@link stormpot.BlazePool BlazePool}.
 *
 * [source,java]
 * --
 * import stormpot.Allocator;
 * import stormpot.Config;
 * import stormpot.LifecycledPool;
 * import stormpot.Poolable;
 * import stormpot.Slot;
 * import stormpot.Timeout;
 * import stormpot.BlazePool;
 * --
 *
 * The next thing we want to do, is to implement our pooled object - in this
 * case our DAO class called +MyDao+. To keep everything in one file, we
 * wrap the lot in a class:
 *
 * [source,java]
 * --
 * include::stormpot/examples/DaoPoolExample.java[tag=defineClass]
 * --
 *
 * Since +MyDao+ is the class of the objects we want to pool, it must implement
 * +Poolable+. To do this, it must have a field of type +Slot+, and a
 * +release+ method. As it is a DAO, we also give it a +Connection+
 * field. We make these fields +final+ and pass their values in
 * through the constructor:
 *
 * [source,java]
 * --
 * include::stormpot/examples/DaoPoolExample.java[tag=mydaoStart]
 * --
 *
 * The contract of the {@link stormpot.Poolable#release()} method is to call
 * the {@link stormpot.Slot#release(Poolable)} method on the slot object that
 * the +Poolable+ was created with. The +Poolable+ that is taken as a parameter to
 * release on +Slot+, is always the +Poolable+ that is being released - the
 * +Poolable+ that was created for this very +Slot+. The +Slot+ takes this parameter
 * to sanity-check that the correct objects are being released for the correct
 * slots, and to prevent errors that might arise from mistakenly releasing an
 * object two times in a row. The simplest possible implementation looks like
 * this:
 *
 * [source,java]
 * --
 * include::stormpot/examples/DaoPoolExample.java[tag=mydaoRelease]
 * --
 *
 * When +release()+ is called, the object returns to the pool. When the object is
 * no longer considered valid, because it got too old, then it is returned to
 * the +Allocator+ through the {@link stormpot.Allocator#deallocate(Poolable)}
 * method. The DAO is holding on to a +Connection+, we would like to have this
 * closed when the object is deallocated. So we add a method that the +Allocator+
 * can call to close the +Connection+, when deallocating an object:
 *
 * [source,java]
 * --
 * include::stormpot/examples/DaoPoolExample.java[tag=mydaoClose]
 * --
 *
 * Private visibility is fine in this case, because we are keeping everything
 * inside a single source file. However, in a more real scenario, you will
 * likely have these classes in multiple source files. In that case,
 * package-protected visibility will probably be a better choice.
 *
 * This is all the ceremony we need to implement +MyDao+. Now we can get to the
 * meat of the class, which would be the DAO methods. As this is just an
 * example, we will only add one method, and make it a stub:
 *
 * [source,java]
 * --
 * include::stormpot/examples/DaoPoolExample.java[tag=mydaoGetFirstname]
 * --
 *
 * And that concludes the +MyDao+ class and the +Poolable+ implementation. Next, we
 * are going to implement our {@link stormpot.Allocator}. The allocator has
 * two responsibilities: First, it must provide the pool implementation with
 * fresh instances of +MyDao+; and second, it must help the pool dispose of
 * objects that are no longer needed. Recall that +MyDao+ objects need two
 * things: A +Slot+ and a +Connection+. The +Slot+ will be passed as a parameter to
 * the {@link stormpot.Allocator#allocate(Slot)} method, and the +Connection+
 * will come from a +DataSource+. We will pass the +Allocator+ that +DataSource+ as
 * a parameter to its constructor, and put it in a +final+ field:
 *
 * [source,java]
 * --
 * include::stormpot/examples/DaoPoolExample.java[tag=allocatorStart]
 * --
 *
 * The +Allocator+ needs an allocate method. It is specified in the API that the
 * +Allocator+ might be used concurrently by multiple threads, and so must be
 * thread-safe. However, the +DataSource+ interface poses no such requirements on
 * its implementers, so we must protect access to it with a lock. Having done
 * that, we can then safely create a new connection and allocate a new +MyDao+
 * instance:
 *
 * [source,java]
 * --
 * include::stormpot/examples/DaoPoolExample.java[tag=allocatorAllocate]
 * --
 *
 * Our +Allocator+ also needs a {@link stormpot.Allocator#deallocate(Poolable)}
 * method. This one is easy to implement. We just call the +close()+ method on our
 * +MyDaos+, and that will close the underlying connection:
 *
 * [source,java]
 * --
 * include::stormpot/examples/DaoPoolExample.java[tag=allocatorClose]
 * --
 *
 * And that concludes our +Allocator+ implementation. We now have the parts
 * needed to pool our +MyDaos+ with Stormpot.
 *
 * We could now just create a +Config+, set the allocator, create a +BlazePool+
 * with it and use that directly. However, it is generally a good idea to not
 * be too dependent on external APIs, because it introduces coupling. So, to
 * reduce this coupling, we are going to introduce an indirection. We are going
 * to create a +MyDaoPool+ class that encapsulates all the Stormpot specific
 * logic, so it doesn't leak into the rest of our code. It will take a
 * +DataSource+ as a constructor parameter and build a pool from it, using the
 * parts we have just built, but it will keep this pool hidden and expose
 * another way of interacting with the +MyDao+ objects.
 *
 * But let us build that pool before we get to that:
 *
 * [source,java]
 * --
 * include::stormpot/examples/DaoPoolExample.java[tag=poolStart]
 * --
 *
 * The set-up is simple: The +DataSource+ goes into our +Allocator+, the +Allocator+
 * into a +Config+, and the +Config+ into a +Pool+ implementation - +BlazePool+ in
 * this case. The +pool+ field is +final+ because we don't need to change it once
 * set, and because it has nice memory visibility semantics so we can safely share
 * +MyDaoPool+ instances among many threads.
 *
 * We have decided to code against the +LifecycledPool+ interface, because we
 * want to support a clean shut-down in our code. When our program shuts down,
 * then so should our pool. When this happens, we will initiate the shut-down
 * procedure of our pool, and wait for it to complete. To do this, we will add
 * a close method to our +MyDaoPool+ class, so we don't have to expose our
 * +LifecycledPool+ field for this purpose:
 *
 * [source,java]
 * --
 * include::stormpot/examples/DaoPoolExample.java[tag=poolClose]
 * --
 *
 * Next, we need a way to interact with the +MyDao+ instances that are managed by
 * the pool inside our +MyDaoPool+. We could simply delegate the +claim()+ methods,
 * but then the release method from the +Poolable+ interface would leak out from
 * our abstraction. We would also like to make sure, once and for all, that
 * release is properly called. If we leak objects out of the pool, we will no
 * longer be able to shut it down.
 *
 * It turns out that there is a way that we can solve both of these problems
 * in one go: We can add a method to the +MyDaoPool+ class, that takes an
 * instance of a function-like interface as a parameter. This method can then
 * handle the mechanics of claiming a +MyDao+ instance, calling the function with
 * it as a parameter, and then releasing it. The function type will be defined
 * as an interface, and the return value of the function can be passed back
 * through the method:
 *
 * [source,java]
 * --
 * include::stormpot/examples/DaoPoolExample.java[tag=poolDoWithDao]
 * --
 *
 * And that concludes our +MyDaoPool+ class. Our use of Stormpot has been hidden
 * behind a nice API. Now it is just a small matter of using the code:
 *
 * [source,java]
 * --
 * include::stormpot/examples/DaoPoolExample.java[tag=main]
 * --
 *
 * The implementation of the +configureDataSource+ method is left as an
 * exercise for the reader.
 *
 *
 * Memory Effects and Threading
 * ============================
 *
 * When configured within the bounds of the {@link stormpot.Config standard
 * configuration}, the Stormpot pooling library will exhibit and guarantee a number
 * of memory effects, that can be relied upon in concurrent and multi-threaded
 * programs.
 *
 * ## *Happens-Before* Edges
 *
 * 1. The {@link stormpot.Allocator#allocate(Slot) allocation} of an object
 * *happens-before* any {@link stormpot.Pool#claim(Timeout) claim} of that object.
 * 2. The claim of an object *happens-before* any subsequent release of the object.
 * 3. The {@link stormpot.Poolable#release() release} of an object *happens-before*
 * any subsequent claim of that object.
 * 4. The release of an object *happens-before* the
 * {@link stormpot.Allocator#deallocate(Poolable) deallocation} of that object.
 * 5. For {@link stormpot.LifecycledPool life-cycled pools}, the deallocation of
 * all objects *happens-before* the
 * {@link stormpot.Completion#await(Timeout) await of a shutdown completion} returns.
 *
 * ## Interruption
 *
 * The (only two) blocking methods, {@link stormpot.Pool#claim(Timeout)} and
 * {@link stormpot.Completion#await(Timeout)}, behave correctly with respect to
 * interruption: If a thread is interrupted when it calls one of these methods, or
 * the thread is interrupted while waiting in one these methods, then an
 * {@link java.lang.InterruptedException} will be thrown, and the threads
 * interruption flag will be cleared.
 */
package stormpot;
