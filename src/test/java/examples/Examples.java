/*
 * Copyright © 2011-2019 Chris Vest (mr.chrisvest@gmail.com)
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
package examples;

import org.junit.jupiter.api.Test;
import stormpot.*;

import javax.management.MBeanServer;
import javax.management.ObjectName;
import java.lang.management.ManagementFactory;
import java.util.concurrent.TimeUnit;

class Examples {
  static class MyPoolable extends BasePoolable {
    MyPoolable(Slot slot) {
      super(slot);
    }
  }

  static class MyAllocator implements Allocator<MyPoolable> {
    @Override
    public MyPoolable allocate(Slot slot) {
      return new MyPoolable(slot);
    }

    @Override
    public void deallocate(MyPoolable poolable) {
    }
  }

  private static final Timeout TIMEOUT = new Timeout(1, TimeUnit.SECONDS);

  @Test
  void managedPoolExample() throws Exception {
    // tag::managedPoolExample[]
    Pool<MyPoolable> pool = Pool.from(new MyAllocator()).build();
    MBeanServer server = ManagementFactory.getPlatformMBeanServer();
    ObjectName name = new ObjectName("com.myapp:objectpool=stormpot");
    server.registerMBean(pool.getManagedPool(), name);
    // end::managedPoolExample[]
  }

  @SuppressWarnings("EmptyTryBlock")
  @Test
  void poolClaimExample() throws Exception {
    Pool<MyPoolable> pool = Pool.from(new MyAllocator()).build();
    // tag::poolClaimExample[]
    Timeout timeout = new Timeout(1, TimeUnit.SECONDS);
    MyPoolable obj = pool.claim(timeout);
    try {
      // Do useful things with 'obj'.
      // Note that 'obj' will be 'null' if 'claim' timed out.
    } finally {
      if (obj != null) {
        obj.release();
      }
    }
    // end::poolClaimExample[]
  }

  @Test
  void poolClaimPrintExample() throws Exception {
    Pool<MyPoolable> pool = Pool.from(new MyAllocator()).build();

    // tag::poolClaimPrintExample[]
    Poolable obj = pool.claim(TIMEOUT);
    if (obj != null) {
      try {
        System.out.println(obj);
      } finally {
        obj.release();
      }
    }
    // end::poolClaimPrintExample[]
  }

  @SuppressWarnings("InnerClassMayBeStatic")
  // tag::poolableGenericExample[]
  public class GenericPoolable implements Poolable {
    private final Slot slot;
    public GenericPoolable(Slot slot) {
      this.slot = slot;
    }

    public void release() {
      slot.release(this);
    }
  }
  // end::poolableGenericExample[]

  @SuppressWarnings({"InnerClassMayBeStatic", "unused"})
  // tag::poolableBaseExample[]
  public class CustomPoolable extends BasePoolable {
    public CustomPoolable(Slot slot) {
      super(slot);
    }
  }
  // end::poolableBaseExample[]

  @SuppressWarnings("unused")
  static class ReallocatorExample<T extends Poolable> implements Reallocator<T> {

    @Override
    public T reallocate(Slot slot, T poolable) {
      // tag::reallocatorExample[]
      deallocate(poolable);
      return allocate(slot);
      // end::reallocatorExample[]
    }

    @Override
    public T allocate(Slot slot) {
      return null;
    }

    @Override
    public void deallocate(T poolable) {
    }
  }
}
