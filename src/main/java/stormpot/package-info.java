
// GENERATED FILE!
// Look at package-info.md in project root, and gen-package-info.py
/**
<p>Stormpot is a generic and thread-safe object pooling library.</p>

<p><strong>Contents:</strong></p>

<p><ul>
  <li><a href="#introduction">Introduction</a></li>
  <li><a href="#user-guide">User Guide</a></li>
</ul>
</p>

<h1 id="introduction">Introduction</h1>

<p>Stormpot is an object pooling library for Java. It consists of an API, and
a number of implementations of that API. As such, Stormpot solves pretty
much the same problem as <a href="http://commons.apache.org/pool/">Apache Commons-Pool</a>. The main differences are
these:</p>

<ul>
<li>Stormpot has a slightly more invasive API.</li>
<li>Stormpot depends on Java5 or newer, whereas Commons-Pool needs at least
Java 1.3</li>
<li>Stormpot has a simpler API with fewer methods, whereas Commons-Pool has
a much larger API surface.</li>
<li>Stormpot pools are guaranteed to be thread-safe, whereas thread-safety
in Commons-Pool is up to the individual implementations.</li>
<li>Stormpot pools prefer to allocate objects in a "back-ground" thread,
whereas Commons-Pool prefer that objects are explicitly added to the
pools</li>
<li>Commons-Pool supports custom object invalidation mechanisms, whereas
Stormpot invalidates objects based on their age.</li>
<li>Commons-Pool has support for keyed pools, akin to caches, whereas
Stormpot does not.</li>
<li>The Stormpot API is slanted towards high through-put. The Commons-Pool
API is slanted towards a rich feature set.</li>
</ul>

<p>Apart from the differences, there are also a number of similarities:</p>

<ul>
<li>Both have high test coverage.</li>
<li>Both have small code-bases.</li>
<li>Both have no dependencies on anything other than Java itself.</li>
<li>Both are licensed under the Apache 2.0 license.</li>
<li>Both have thorough API documentation.</li>
</ul>

<p>So those are the things to keep in mind, when deciding on which pool to use.</p>

<h1 id="user-guide">User Guide</h1>

<p>As mentioned in the introduction, Stormpot has an invasive API. This means
that there are things you have to do, before you can make use of its pooling
capabilities. In this section, we will take a look at these things, and see
what it takes to implement pooling of some DAOs. This is just an
example, but it will touch on all of the basics you need to know, in order
to get started with Stormpot.</p>

<p>We are going to pool Data Access Objects, or DAOs, and each of these will
work with a database {@link java.sql.Connection connection} that comes from
a {@link javax.sql.DataSource}. So let us start off by importing those:</p>

<div class="codehilite"><pre><code><span class="cckn">import</span> <span class="ccnn">java.sql.Connection</span><span class="cco">;</span>
<span class="cckn">import</span> <span class="ccnn">java.sql.SQLException</span><span class="cco">;</span>

<span class="cckn">import</span> <span class="ccnn">javax.sql.DataSource</span><span class="cco">;</span>
</code></pre></div>

<p>We are also going to need most of the Stormpot API. We are going to need
{@link stormpot.Config} for our pool configuration;
{@link stormpot.LifecycledPool} is the pool interface we will code against,
because we want our code to have a clean shut-down path;
{@link stormpot.Allocator} and {@link stormpot.Poolable} are interfaces we
are going to have to implement in our own code, and we will be needing the
{@link stormpot.Slot} interface to do that; and finally we are going to
need a concrete pool implementation from the library:
{@link stormpot.qpool.QueuePool}.</p>

<div class="codehilite"><pre><code><span class="cckn">import</span> <span class="ccnn">stormpot.Allocator</span><span class="cco">;</span>
<span class="cckn">import</span> <span class="ccnn">stormpot.Config</span><span class="cco">;</span>
<span class="cckn">import</span> <span class="ccnn">stormpot.LifecycledPool</span><span class="cco">;</span>
<span class="cckn">import</span> <span class="ccnn">stormpot.Poolable</span><span class="cco">;</span>
<span class="cckn">import</span> <span class="ccnn">stormpot.Slot</span><span class="cco">;</span>
<span class="cckn">import</span> <span class="ccnn">stormpot.qpool.QueuePool</span><span class="cco">;</span>
</code></pre></div>

<p>The next thing we want to do, is to implement our pooled object - in this
case our DAO class called MyDao. To keep everything in one file, we
wrap the lot in a class:</p>

<div class="codehilite"><pre><code><span class="cckd">public</span> <span class="cckd">class</span> <span class="ccnc">DaoPoolExample</span> <span class="cco">{</span>
</code></pre></div>

<p>Since MyDao is the class of the objects we want to pool, it must implement
Poolable. To do this, it must have a field of type Slot, and a
<code>release</code> method. As it is a DAO, we also give it a Connection
field. We make these fields <code>final</code> and pass their values in
through the constructor:</p>

<div class="codehilite"><pre><code>  <span class="cckd">static</span> <span class="cckd">class</span> <span class="ccnc">MyDao</span> <span class="cckd">implements</span> <span class="ccn">Poolable</span> <span class="cco">{</span>
  <span class="cckd">private</span> <span class="cckd">final</span> <span class="ccn">Slot</span> <span class="ccn">slot</span><span class="cco">;</span>
  <span class="cckd">private</span> <span class="cckd">final</span> <span class="ccn">Connection</span> <span class="ccn">connection</span><span class="cco">;</span>

  <span class="cckd">private</span> <span class="ccnf">MyDao</span><span class="cco">(</span><span class="ccn">Slot</span> <span class="ccn">slot</span><span class="cco">,</span> <span class="ccn">Connection</span> <span class="ccn">connection</span><span class="cco">)</span> <span class="cco">{</span>
    <span class="cck">this</span><span class="cco">.</span><span class="ccna">slot</span> <span class="cco">=</span> <span class="ccn">slot</span><span class="cco">;</span>
    <span class="cck">this</span><span class="cco">.</span><span class="ccna">connection</span> <span class="cco">=</span> <span class="ccn">connection</span><span class="cco">;</span>
  <span class="cco">}</span>
</code></pre></div>

<p>The contract of the {@link stormpot.Poolable#release()} method is to call
the {@link stormpot.Slot#release(Poolable)} method on the slot object that
the Poolable was created with. The Poolable that is taken as a parameter to
release on Slot, is always the Poolable that is being released - the
Poolable that was created for this very Slot. The Slot takes this parameter
to sanity-check that the correct objects are being released for the correct
slots, and to prevent errors that might arise from mistakenly releasing an
object two times in a row. The simplest possible implementation looks like
this:</p>

<div class="codehilite"><pre><code>    <span class="cckd">public</span> <span class="cckt">void</span> <span class="ccnf">release</span><span class="cco">()</span> <span class="cco">{</span>
      <span class="ccn">slot</span><span class="cco">.</span><span class="ccna">release</span><span class="cco">(</span><span class="cck">this</span><span class="cco">);</span>
    <span class="cco">}</span>
</code></pre></div>

<p>When release is called, the object returns to the pool. When the object is
no longer considered valid, because it got too old, then it is returned to
the Allocator through the {@link stormpot.Allocator#deallocate(Poolable)}
method. The DAO is holding on to a Connection, we would like to have this
closed when the object is deallocated. So we add a method that the Allocator
can call to close the Connection, when deallocating an object:</p>

<div class="codehilite"><pre><code>    <span class="cckd">private</span> <span class="cckt">void</span> <span class="ccnf">close</span><span class="cco">()</span> <span class="cckd">throws</span> <span class="ccn">SQLException</span> <span class="cco">{</span>
      <span class="ccn">connection</span><span class="cco">.</span><span class="ccna">close</span><span class="cco">();</span>
    <span class="cco">}</span>
</code></pre></div>

<p>Private visibility is fine in this case, because we are keeping everything
inside a single source file. However, in a more real scenario, you will
likely have these classes in multiple source files. In that case,
package-protected visibility will probably be a better choice.</p>

<p>This is all the ceremony we need to implement <code>MyDao</code>. Now we can get to the
meat of the class, which would be the DAO methods. As this is just an
example, we will only add one method, and make it a stub:</p>

<div class="codehilite"><pre><code>    <span class="cckd">public</span> <span class="ccn">String</span> <span class="ccnf">getFirstName</span><span class="cco">()</span> <span class="cco">{</span>
      <span class="ccc1">// Stub: get the name from the database using the connection.</span>
      <span class="ccc1">// But for now, just always return &quot;freddy&quot;</span>
      <span class="cck">return</span> <span class="ccs">&quot;freddy&quot;</span><span class="cco">;</span>
    <span class="cco">}</span>
  <span class="cco">}</span>
</code></pre></div>

<p>And that concludes the MyDao class and the Poolable implementation. Next, we
are going to implement our {@link stormpot.Allocator}. The allocator has
two responsibilities: First, it must provide the pool implementation with
fresh instances of MyDao; and second, it must help the pool dispose of
objects that are no longer needed. Recall that MyDao objects need two
things: A Slot and a Connection. The Slot will be passed as a parameter to
the {@link stormpot.Allocator#allocate(Slot)} method, and the Connection
will come from a DataSource. We will pass the Allocator that DataSource as
a parameter to its constructor, and put it in a <code>final</code> field:</p>

<div class="codehilite"><pre><code>  <span class="cckd">static</span> <span class="cckd">class</span> <span class="ccnc">MyDaoAllocator</span> <span class="cckd">implements</span> <span class="ccn">Allocator</span><span class="cco">&amp;</span><span class="ccn">lt</span><span class="cco">;</span><span class="ccn">MyDao</span><span class="cco">&amp;</span><span class="ccn">gt</span><span class="cco">;</span> <span class="cco">{</span>
    <span class="cckd">private</span> <span class="cckd">final</span> <span class="ccn">DataSource</span> <span class="ccn">dataSource</span><span class="cco">;</span>

    <span class="cckd">public</span> <span class="ccnf">MyDaoAllocator</span><span class="cco">(</span><span class="ccn">DataSource</span> <span class="ccn">dataSource</span><span class="cco">)</span> <span class="cco">{</span>
      <span class="cck">this</span><span class="cco">.</span><span class="ccna">dataSource</span> <span class="cco">=</span> <span class="ccn">dataSource</span><span class="cco">;</span>
    <span class="cco">}</span>
</code></pre></div>

<p>The Allocator needs an allocate method. It is specified in the API that the
Allocator might be used concurrently by multiple threads, and so must be
thread-safe. However, the DataSource interface poses no such requirements on
its implementors, so we must protect access to it with a lock. Having done
that, we can then safely create a new connection and allocate a new <code>MyDao</code>
instance:</p>

<div class="codehilite"><pre><code>    <span class="cckd">public</span> <span class="ccn">MyDao</span> <span class="ccnf">allocate</span><span class="cco">(</span><span class="ccn">Slot</span> <span class="ccn">slot</span><span class="cco">)</span> <span class="cckd">throws</span> <span class="ccn">Exception</span> <span class="cco">{</span>
      <span class="cckd">synchronized</span> <span class="cco">(</span><span class="ccn">dataSource</span><span class="cco">)</span> <span class="cco">{</span>
        <span class="cck">return</span> <span class="cck">new</span> <span class="ccnf">MyDao</span><span class="cco">(</span><span class="ccn">slot</span><span class="cco">,</span> <span class="ccn">dataSource</span><span class="cco">.</span><span class="ccna">getConnection</span><span class="cco">());</span>
      <span class="cco">}</span>
    <span class="cco">}</span>
</code></pre></div>

<p>Our Allocator also needs a {@link stormpot.Allocator#deallocate(Poolable)}
method. This one is easy to implement. We just call the close method on our
MyDaos, and that will close the underlying connection:</p>

<div class="codehilite"><pre><code>    <span class="cckd">public</span> <span class="cckt">void</span> <span class="ccnf">deallocate</span><span class="cco">(</span><span class="ccn">MyDao</span> <span class="ccn">poolable</span><span class="cco">)</span> <span class="cckd">throws</span> <span class="ccn">Exception</span> <span class="cco">{</span>
      <span class="ccn">poolable</span><span class="cco">.</span><span class="ccna">close</span><span class="cco">();</span>
    <span class="cco">}</span>
  <span class="cco">}</span>
</code></pre></div>

<p>And that concludes our Allocator implementation. We now have the parts
needed to pool our <code>MyDaos</code> with Stormpot.</p>

<p>We could now just create a Config, set the allocator, create a QueuePool
with it and use that directly. However, it is generally a good idea to not
be too dependent on external APIs, because it introduces coupling. So, to
reduce this coupling, we are going to introduce an indirection. We are going
to create a <code>MyDaoPool</code> class that encapsulates all the Stormpot specific
logic, so it doesn't leak into the rest of our code. It will take a
DataSource as a constructor parameter and build a pool from it, using the
parts we have just built, but it will keep this pool hidden and expose
another way of interacting with the <code>MyDao</code> objects.</p>

<p>But let us build that pool before we get to that:</p>

<div class="codehilite"><pre><code>  <span class="cckd">static</span> <span class="cckd">class</span> <span class="ccnc">MyDaoPool</span> <span class="cco">{</span>
    <span class="cckd">private</span> <span class="cckd">final</span> <span class="ccn">LifecycledPool</span><span class="cco">&amp;</span><span class="ccn">lt</span><span class="cco">;</span><span class="ccn">MyDao</span><span class="cco">&amp;</span><span class="ccn">gt</span><span class="cco">;</span> <span class="ccn">pool</span><span class="cco">;</span>

    <span class="cckd">public</span> <span class="ccnf">MyDaoPool</span><span class="cco">(</span><span class="ccn">DataSource</span> <span class="ccn">dataSource</span><span class="cco">)</span> <span class="cco">{</span>
      <span class="ccn">MyDaoAllocator</span> <span class="ccn">allocator</span> <span class="cco">=</span> <span class="cck">new</span> <span class="ccn">MyDaoAllocator</span><span class="cco">(</span><span class="ccn">dataSource</span><span class="cco">);</span>
      <span class="ccn">Config</span><span class="cco">&amp;</span><span class="ccn">lt</span><span class="cco">;</span><span class="ccn">MyDao</span><span class="cco">&amp;</span><span class="ccn">gt</span><span class="cco">;</span> <span class="ccn">config</span> <span class="cco">=</span> <span class="cck">new</span> <span class="ccn">Config</span><span class="cco">&amp;</span><span class="ccn">lt</span><span class="cco">;</span><span class="ccn">MyDao</span><span class="cco">&amp;</span><span class="ccn">gt</span><span class="cco">;().</span><span class="ccna">setAllocator</span><span class="cco">(</span><span class="ccn">allocator</span><span class="cco">);</span>
      <span class="ccn">pool</span> <span class="cco">=</span> <span class="cck">new</span> <span class="ccn">QueuePool</span><span class="cco">&amp;</span><span class="ccn">lt</span><span class="cco">;</span><span class="ccn">MyDao</span><span class="cco">&amp;</span><span class="ccn">gt</span><span class="cco">;(</span><span class="ccn">config</span><span class="cco">);</span>
    <span class="cco">}</span>
</code></pre></div>

<p>The set-up is simple: The DataSource goes into our Allocator, the Allocator
into a Config, and the Config into a Pool implementation - QueuePool in
this case. The <code>pool</code> field is final because we don't need to
change it once set, and because it has nice memory visibility semantics so
we can safely share <code>MyDaoPool</code> instances among many threads.</p>

<p>We have decided to code against the <code>LifecycledPool</code> interface, because we
want to support a clean shut-down in our code. When our program shuts down,
then so should our pool. When this happens, we will initiate the shut-down
procedure of our pool, and wait for it to complete. To do this, we will add
a close method to our <code>MyDaoPool</code> class, so we don't have to expose our
<code>LifecycledPool</code> field for this purpose:</p>

<div class="codehilite"><pre><code>    <span class="cckd">public</span> <span class="cckt">void</span> <span class="ccnf">close</span><span class="cco">()</span> <span class="cckd">throws</span> <span class="ccn">InterruptedException</span> <span class="cco">{</span>
      <span class="ccn">pool</span><span class="cco">.</span><span class="ccna">shutdown</span><span class="cco">().</span><span class="ccna">await</span><span class="cco">();</span>
    <span class="cco">}</span>
</code></pre></div>

<p>Next, we need a way to interact with the <code>MyDao</code> instances that are managed by
the pool inside our <code>MyDaoPool</code>. We could simply delegate the claim methods,
but then the release method from the <code>Poolable</code> interface would leak out from
our abstraction. We would also like to make sure, once and for all, that
release is properly called. If we leak objects out of the pool, we will no
longer be able to shut it down.</p>

<p>It turns out that there is a way that we can solve both of these problems
in one go: We can add a method to the <code>MyDaoPool</code> class, that takes an
instance of a function-like interface as a parameter. This method can then
handle the mechanics of claiming a <code>MyDao</code> instance, calling the function with
it as a parameter, and then releasing it. The function type will be defined
as an interface, and the return value of the function can be passed back
through the method:</p>

<div class="codehilite"><pre><code>    <span class="cckd">public</span> <span class="cco">&amp;</span><span class="ccn">lt</span><span class="cco">;</span><span class="ccn">T</span><span class="cco">&amp;</span><span class="ccn">gt</span><span class="cco">;</span> <span class="ccn">T</span> <span class="ccn">doWithDao</span><span class="cco">(</span><span class="ccn">WithMyDaoDo</span><span class="cco">&amp;</span><span class="ccn">lt</span><span class="cco">;</span><span class="ccn">T</span><span class="cco">&amp;</span><span class="ccn">gt</span><span class="cco">;</span> <span class="ccn">action</span><span class="cco">)</span>
    <span class="cckd">throws</span> <span class="ccn">InterruptedException</span> <span class="cco">{</span>
      <span class="ccn">MyDao</span> <span class="ccn">dao</span> <span class="cco">=</span> <span class="ccn">pool</span><span class="cco">.</span><span class="ccna">claim</span><span class="cco">();</span>
      <span class="cck">try</span> <span class="cco">{</span>
        <span class="cck">return</span> <span class="ccn">action</span><span class="cco">.</span><span class="ccna">doWithDao</span><span class="cco">(</span><span class="ccn">dao</span><span class="cco">);</span>
      <span class="cco">}</span> <span class="cck">finally</span> <span class="cco">{</span>
        <span class="ccn">dao</span><span class="cco">.</span><span class="ccna">release</span><span class="cco">();</span>
      <span class="cco">}</span>
    <span class="cco">}</span>
  <span class="cco">}</span>

  <span class="cckd">static</span> <span class="cckd">interface</span> <span class="ccnc">WithMyDaoDo</span><span class="cco">&amp;</span><span class="ccn">lt</span><span class="cco">;</span><span class="ccn">T</span><span class="cco">&amp;</span><span class="ccn">gt</span><span class="cco">;</span> <span class="cco">{</span>
    <span class="cckd">public</span> <span class="ccn">T</span> <span class="ccnf">doWithDao</span><span class="cco">(</span><span class="ccn">MyDao</span> <span class="ccn">dao</span><span class="cco">);</span>
  <span class="cco">}</span>
</code></pre></div>

<p>And that concludes our <code>MyDaoPool</code> class. Our use of Stormpot has been hidden
behind a nice API. Now it is just a small matter of using the code:</p>

<div class="codehilite"><pre><code>  <span class="cckd">public</span> <span class="cckd">static</span> <span class="cckt">void</span> <span class="ccnf">main</span><span class="cco">(</span><span class="ccn">String</span><span class="cco">[]</span> <span class="ccn">args</span><span class="cco">)</span> <span class="cckd">throws</span> <span class="ccn">InterruptedException</span> <span class="cco">{</span>
    <span class="ccn">DataSource</span> <span class="ccn">dataSource</span> <span class="cco">=</span> <span class="ccn">configureDataSource</span><span class="cco">();</span>
    <span class="ccn">MyDaoPool</span> <span class="ccn">pool</span> <span class="cco">=</span> <span class="cck">new</span> <span class="ccn">MyDaoPool</span><span class="cco">(</span><span class="ccn">dataSource</span><span class="cco">);</span>
    <span class="ccn">String</span> <span class="ccn">person</span> <span class="cco">=</span> <span class="ccn">pool</span><span class="cco">.</span><span class="ccna">doWithDao</span><span class="cco">(</span><span class="cck">new</span> <span class="ccn">WithMyDaoDo</span><span class="cco">&lt;</span><span class="ccn">String</span><span class="cco">&gt;()</span> <span class="cco">{</span>
      <span class="cckd">public</span> <span class="ccn">String</span> <span class="ccnf">doWithDao</span><span class="cco">(</span><span class="ccn">MyDao</span> <span class="ccn">dao</span><span class="cco">)</span> <span class="cco">{</span>
        <span class="cck">return</span> <span class="ccn">dao</span><span class="cco">.</span><span class="ccna">getFirstName</span><span class="cco">();</span>
      <span class="cco">}</span>
    <span class="cco">});</span>
    <span class="ccn">System</span><span class="cco">.</span><span class="ccna">out</span><span class="cco">.</span><span class="ccna">println</span><span class="cco">(</span><span class="ccs">&quot;Hello there, &quot;</span> <span class="cco">+</span> <span class="ccn">person</span> <span class="cco">+</span> <span class="ccs">&quot;!&quot;</span><span class="cco">);</span>
    <span class="ccn">pool</span><span class="cco">.</span><span class="ccna">close</span><span class="cco">();</span>
  <span class="cco">}</span>
<span class="cco">}</span>
</code></pre></div>

<p>The implementation of the <code>configureDataSource</code> method is left as an
exercise for the reader.</p>

*/
package stormpot;
