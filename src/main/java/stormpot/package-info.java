/**
 * Stormpot is a generic and thread-safe object pooling library.
 * <p>
 * <strong>Contents:</strong>
 * </p>
 * <ol>
 * <li><a href="#intro">Introduction</a></li>
 * <li><a href="#guide">User Guide</a></li>
 * </ol>
 * 
 * <h1><a name="intro">Introduction</a></h1>
 * <p>
 * Stormpot is an object pooling library for Java. It consists of an API, and
 * a number of implementations of that API. As such, Stormpot solves pretty
 * much the same problem as <a href="http://commons.apache.org/pool/">
 * Apache Commons-Pool</a>. The main differences are these:
 * </p>
 * <ul>
 * <li>Stormpot has a slightly more invasive API.</li>
 * <li>Stormpot depends on Java5 or newer, whereas Commons-Pool needs at least
 * Java 1.3</li>
 * <li>Stormpot has a simpler API with fewer methods, whereas Commons-Pool has
 * a much larger API surface.</li>
 * <li>Stormpot pools are guaranteed to be thread-safe, whereas thread-safety
 * in Commons-Pool is up to the individual implementations.</li>
 * <li>Stormpot pools prefer to allocate objects in a "back-ground" thread,
 * whereas Commons-Pool prefer that objects are explicitly added to the
 * pools</li>
 * <li>Commons-Pool supports custom object invalidation mechanisms, whereas
 * Stormpot invalidates objects based on their age.</li>
 * <li>Commons-Pool has support for keyed pools, akin to caches, whereas
 * Stormpot does not.</li>
 * <li>The Stormpot API is slanted towards high through-put. The Commons-Pool
 * API is slanted towards a rich feature set.</li>
 * </ul>
 * <p>
 * Apart from the differences, there are also a number of similarities:
 * </p>
 * <ul>
 * <li>Both have high test coverage.</li>
 * <li>Both have small code-bases.</li>
 * <li>Both have no dependencies on anything other than Java itself.</li>
 * <li>Both are licensed under the Apache 2.0 license.</li>
 * <li>Both have thorough API documentation.</li>
 * </ul>
 * <p>
 * So those are the things to keep in mind, when deciding on which pool to use.
 * </p>
 * 
 * <h1><a name="guide">User Guide</a></h1>
 * <p>
 * As mentioned in the introduction, Stormpot has an invasive API. This means
 * that there are things you have to do, before you can make use of its pooling
 * capabilities. In this section, we will take a look at these things, and see
 * what it takes to implement pooling of some DAOs. This is just an
 * example, but it will touch on all of the basics you need to know, in order
 * to get started with Stormpot.
 * </p><p>
 * We are going to pool Data Access Objects, or DAOs, and each of these will
 * work with a database {@link java.sql.Connection connection} that comes from
 * a {@link javax.sql.DataSource}. So let us start off by importing those:
 * </p>
 * <pre><code> import java.sql.Connection;
 * import java.sql.SQLException;
 * 
 * import javax.sql.DataSource;</code></pre>
 * <p>
 * We are also going to need most of the Stormpot API. We are going to need
 * {@link stormpot.Config} for our pool configuration;
 * {@link stormpot.LifecycledPool} is the pool interface we will code against,
 * because we want our code to have a clean shut-down path;
 * {@link stormpot.Allocator} and {@link stormpot.Poolable} are interfaces we
 * are going to have to implement in our own code, and we will be needing the
 * {@link stormpot.Slot} interface to do that; and finally we are going to
 * need a concrete pool implementation from the library:
 * {@link stormpot.qpool.QueuePool}.
 * </p>
 * <pre><code> import stormpot.Allocator;
 * import stormpot.Config;
 * import stormpot.LifecycledPool;
 * import stormpot.Poolable;
 * import stormpot.Slot;
 * import stormpot.qpool.QueuePool;</code></pre>
 * <p>
 * The next thing we want to do, is to implement our pooled object - in this
 * case our DAO class called MyDao. To keep everything in one file, we
 * wrap the lot in a class:
 * </p>
 * <pre><code> public class DaoPoolExample {</code></pre>
 * <p>
 * Since MyDao is the class of the objects we want to pool, it must implement
 * Poolable. To do this, it must have a field of type Slot, and a
 * <code>release</code> method. As it is a DAO, we also give it a Connection
 * field. We make these fields <code>final</code> and pass their values in
 * through the constructor:
 * </p>
 * <pre><code>   static class MyDao implements Poolable {
 *    private final Slot slot;
 *    private final Connection connection;
 *    
 *    private MyDao(Slot slot, Connection connection) {
 *      this.slot = slot;
 *      this.connection = connection;
 *    }</code></pre>
 * <p>
 * The contract of the {@link stormpot.Poolable#release()} method is to call
 * the {@link stormpto.Slot#release(Poolable)} method on the slot object that
 * the Poolable was created with. The Poolable that is taken as a parameter to
 * release on Slot, is always the Poolable that is being released - the
 * Poolable that was created for this very Slot. The Slot takes this parameter
 * to sanity-check that the correct objects are being released for the correct
 * slots, and to prevent errors that might arise from mistakenly releasing an
 * object two times in a row. The simplest possible implementation looks like
 * this:
 * </p><pre><code>     public void release() {
 *      slot.release(this);
 *    }</code></pre>
 * <p>
 * When release is called, the object returns to the pool. When the object is
 * no longer considered valid, because it got too old, then it is returned to
 * the Allocator through the {@link stormpot.Allocator#deallocate(Poolable)}
 * method. The DAO is holding on to a Connection, we would like to have this
 * closed when the object is deallocated. So we add a method that the Allocator
 * can call to close the Connection, when deallocating an object:
 * </p><pre><code>     private void close() throws SQLException {
 *      connection.close();
 *    }</code></pre>
 * <p>
 * Private visibility is fine in this case, because we are keeping everything
 * inside a single source file. However, in a more real scenario, you will
 * likely have these classes in multiple source files. In that case,
 * package-protected visibility will probably be a better choice.
 * <p>
 * This is all the ceremony we need to implement MyDao. Now we can get to the
 * meat of the class, which would be the DAO methods. As this is just an
 * example, we will only add one method, and make it a stub:
 * </p><pre><code>     public String getFirstName() {
 *      // Stub: get the name from the database using the connection.
 *      // But for now, just always return "freddy"
 *      return "freddy";
 *    }
 *  }</code></pre>
 * <p>
 * And that concludes the MyDao class and the Poolable implementation. Next, we
 * are going to implement our {@link stormpot.Allocator}.
 */
package stormpot;
