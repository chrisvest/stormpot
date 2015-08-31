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
package stormpot;

import org.junit.Ignore;
import org.junit.Test;

import java.lang.ref.WeakReference;

import static org.junit.Assert.assertNull;

public class ThreadLocalWeakRefTest {
  /**
   * Many Java web servers and containers go out of their way to find and null
   * out ThreadLocals that an application might have created. Question is, is
   * that really necessary?
   */
  @Ignore
  @Test public void
  threadsWeaklyReferenceThreadLocalValues() {
    // Hypothesis: When we loose the reference to a ThreadLocal (e.g. by a
    // web app being redeployed and getting a soft restart), and no other
    // references to that object exists, then it should eventually be garbage
    // collected.

    Object obj = new Object();
    ThreadLocal<Object> threadLocal = new ThreadLocal<>();
    WeakReference<Object> weakReference = new WeakReference<>(obj);
    threadLocal.set(obj);

    // The stack of every thread is a GC-root. We have a strong reference to
    // obj through the variable on our stack. When we clear it out, the only
    // strong reference that will be left, will be through the threadLocal.
    obj = null;

    // When we loose the reference to the threadLocal, then the values it
    // referred to will no longer be strongly referenced by our thread.
    threadLocal = null;

    // Our obj is now in principle garbage, though we still have a
    // weakReference to it. The GC does not yet know this, because only the
    // threadLocal object itself is now weakly referenced. We clear out that
    // weak reference with a run of the GC.
    System.gc();

    // The entry in our ThreadLocalMap is now stale. It still exists, and
    // strongly references our obj, but it will be cleared next time our
    // ThreadLocalMap does its clean-up procedure. We cannot invoke the
    // clean-up procedure directly, but sufficient perturbation of the
    // ThreadLocalMap will bring it about.
    ThreadLocal<Object> a = new ThreadLocal<>();
    a.set(new Object());
    ThreadLocal<Object> b = new ThreadLocal<>();
    b.set(new Object());

    // The entry has now been removed, and our obj is now weakly referenced
    // by our weakReference above. Invoking another round of GC will clear it.
    System.gc();

    // The obj has now been collected because all references are gone.
    // We observe this by the weakReference being null.
    assertNull(weakReference.get());

    // This test passes on Java 6, 7 and 8, with default GC settings.
    // I haven't tried alternative GC settings, but you are welcome to do that.
  }
}
