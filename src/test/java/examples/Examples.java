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
package examples;

import org.junit.jupiter.api.Test;
import stormpot.Allocator;
import stormpot.BasePoolable;
import stormpot.Expiration;
import stormpot.Pool;
import stormpot.Poolable;
import stormpot.Pooled;
import stormpot.Reallocator;
import stormpot.Slot;
import stormpot.SlotInfo;
import stormpot.Timeout;

import javax.management.MBeanServer;
import javax.management.ObjectName;
import java.lang.management.ManagementFactory;

import static java.util.concurrent.TimeUnit.SECONDS;
import static org.assertj.core.api.Assertions.assertThat;

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

  private static final Timeout TIMEOUT = new Timeout(1, SECONDS);

  @Test
  void managedPoolExample() throws Exception {
    // tag::managedPoolExample[]
    // @start region=managedPoolExample
    Pool<MyPoolable> pool = Pool.from(new MyAllocator()).build();
    MBeanServer server = ManagementFactory.getPlatformMBeanServer();
    ObjectName name = new ObjectName("com.myapp:objectpool=stormpot");
    server.registerMBean(pool.getManagedPool(), name);
    // @end
    // end::managedPoolExample[]
  }

  @SuppressWarnings("EmptyTryBlock")
  @Test
  void poolClaimExample() throws Exception {
    Pool<MyPoolable> pool = Pool.from(new MyAllocator()).build();
    // @start region=poolClaimExample
    Timeout timeout = new Timeout(1, SECONDS);
    MyPoolable obj = pool.claim(timeout);
    try {
      // Do useful things with 'obj'.
      // Note that 'obj' will be 'null' if 'claim' timed out.
    } finally {
      if (obj != null) {
        obj.release();
      }
    }
    // @end
  }

  @Test
  void poolClaimPrintExample() throws Exception {
    Pool<MyPoolable> pool = Pool.from(new MyAllocator()).build();

    // @start region=poolClaimPrintExample
    Poolable obj = pool.claim(TIMEOUT);
    if (obj != null) {
      try {
        System.out.println(obj);
      } finally {
        obj.release();
      }
    }
    // @end
  }

  @Test
  void directPoolExample() throws Exception {
    // tag::directPoolExample[]
    Object a = new Object();
    Object b = new Object();
    Object c = new Object();
    Pool<Pooled<Object>> pool = Pool.of(a, b, c);
    try (Pooled<Object> claim = pool.claim(TIMEOUT)) {
      if (claim != null) {
        System.out.println(claim.object);
      }
    }
    // end::directPoolExample[]
  }

  @Test
  void inlinePoolExample() throws Exception {
    MyAllocator allocator = new MyAllocator();
    // tag::inlinePoolExample[]
    Pool<MyPoolable> pool = Pool.fromInline(allocator).build();
    MyPoolable obj = pool.claim(TIMEOUT);
    if (obj != null) {
      System.out.println(obj);
      obj.release();
    }
    // end::inlinePoolExample[]
  }

  @SuppressWarnings("InnerClassMayBeStatic")
  // @start region=poolableGenericExample
  public class GenericPoolable implements Poolable {
    private final Slot slot;
    public GenericPoolable(Slot slot) {
      this.slot = slot;
    }

    @Override
    public void release() {
      slot.release(this);
    }
  }
  // @end

  @SuppressWarnings({"InnerClassMayBeStatic", "unused"})
  // @start region=poolableBaseExample
  public class CustomPoolable extends BasePoolable {
    public CustomPoolable(Slot slot) {
      super(slot);
    }
  }
  // @end

  @SuppressWarnings("unused")
  static class ReallocatorExample<T extends Poolable> implements Reallocator<T> {

    @Override
    public T reallocate(Slot slot, T poolable) {
      // @start region=reallocatorExample
      deallocate(poolable);
      return allocate(slot);
      // @end
    }

    @Override
    public T allocate(Slot slot) {
      return null;
    }

    @Override
    public void deallocate(T poolable) {
    }
  }

  @Test
  void expensiveExpirationWithEveryExample() {
    class PooledConnection extends BasePoolable {
      PooledConnection(Slot slot) {
        super(slot);
      }
    }
    class CheckConnectionExpiration implements Expiration<PooledConnection> {
      @Override
      public boolean hasExpired(SlotInfo<? extends PooledConnection> info) {
        return false;
      }
    }
    Allocator<PooledConnection> connectionAllocator = new Allocator<>() {
      @Override
      public PooledConnection allocate(Slot slot) {
        return new PooledConnection(slot);
      }

      @Override
      public void deallocate(PooledConnection poolable) {
      }
    };

    // tag::expensiveExpirationWithEveryExample[]
    Expiration<PooledConnection> checkConnection = new CheckConnectionExpiration()
        .every(10, SECONDS); // <1>
    Pool<PooledConnection> connectionPool = Pool.from(connectionAllocator)
        .setExpiration(checkConnection)
        .build();
    // end::expensiveExpirationWithEveryExample[]
    assertThat(connectionPool).isNotNull();
  }
}
