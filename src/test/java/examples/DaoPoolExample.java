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
package examples;

import stormpot.Allocator;
import stormpot.Expiration;
import stormpot.Pool;
import stormpot.PoolBuilder;
import stormpot.Poolable;
import stormpot.Slot;
import stormpot.SlotInfo;
import stormpot.Timeout;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.concurrent.TimeUnit;

@SuppressWarnings({"WeakerAccess","unused"})
// tag::defineClass[]
public class DaoPoolExample {
  // end::defineClass[]
  // tag::mydaoStart[]
  static class MyDao implements Poolable {
    final Slot slot;
    final Connection connection;
    
    private MyDao(Slot slot, Connection connection) {
      this.slot = slot;
      this.connection = connection;
    }
    // end::mydaoStart[]
    // tag::mydaoRelease[]
    @Override
    public void release() {
      slot.release(this);
    }
    // end::mydaoRelease[]
    // tag::mydaoClose[]
    private void close() throws SQLException {
      connection.close();
    }
    // end::mydaoClose[]

    // ...
    // public DAO-type methods
    // ...

    // tag::mydaoGetFirstname[]
    public String getFirstName() {
      // Stub: get the name from the database using the connection.
      // But for now, just always return "freddy"
      return "freddy";
    }
  }
  // end::mydaoGetFirstname[]

  // tag::allocatorStart[]
  static class MyDaoAllocator implements Allocator<MyDao> {
    private final DataSource dataSource;
    
    public MyDaoAllocator(DataSource dataSource) {
      this.dataSource = dataSource;
    }
    // end::allocatorStart[]

    // tag::allocatorAllocate[]
    @Override
    public MyDao allocate(Slot slot) throws Exception {
      synchronized (dataSource) {
        return new MyDao(slot, dataSource.getConnection());
      }
    }
    // end::allocatorAllocate[]

    // tag::allocatorClose[]
    @Override
    public void deallocate(MyDao poolable) throws Exception {
      poolable.close();
    }
  }
  // end::allocatorClose[]
  
  static class TestQueryExpiration implements Expiration<MyDao> {
    @Override
    public boolean hasExpired(SlotInfo<? extends MyDao> info) {
      MyDao dao = info.getPoolable();
      Connection con = dao.connection;
      synchronized (con) {
        try {
          try (Statement stmt = con.createStatement()) {
            stmt.execute("select 1 from dual;");
          }
        } catch (SQLException e) {
          return true;
        }
        return false;
      }
    }
  }

  // tag::poolStart[]
  static class MyDaoPool {
    private final Pool<MyDao> pool;
    
    public MyDaoPool(DataSource dataSource) {
      MyDaoAllocator allocator = new MyDaoAllocator(dataSource);
      pool = Pool.from(allocator).build();
    }
    // end::poolStart[]

    public MyDaoPool(DataSource dataSource, boolean verifyConnections) {
      MyDaoAllocator allocator = new MyDaoAllocator(dataSource);
      PoolBuilder<MyDao> builder = Pool.from(allocator);

      if (verifyConnections) {
        builder.setExpiration(new TestQueryExpiration());
      }

      pool = builder.build();
    }

    // tag::poolClose[]
    public void close() throws InterruptedException {
      pool.shutdown().await(new Timeout(1, TimeUnit.MINUTES));
    }
    // end::poolClose[]

    // tag::poolDoWithDao[]
    public <T> T doWithDao(WithMyDaoDo<T> action)
        throws InterruptedException {
      MyDao dao = pool.claim(new Timeout(1, TimeUnit.SECONDS));
      try {
        return action.doWithDao(dao);
      } finally {
        dao.release();
      }
    }
  }
  
  interface WithMyDaoDo<T> {
    T doWithDao(MyDao dao);
  }
  // end::poolDoWithDao[]

  // tag::main[]
  public static void main(String[] args) throws InterruptedException {
    DataSource dataSource = configureDataSource();
    MyDaoPool pool = new MyDaoPool(dataSource);
    try {
      String person = pool.doWithDao(MyDao::getFirstName);
      System.out.println("Hello there, " + person + "!");
    } finally {
      pool.close();
    }
  }
  // end::main[]

  private static DataSource configureDataSource() {
    // Stub: configure and return some kind of DataSource object.
    // Allocation will fail with a NullPointerException until this method
    // get a proper implementation.
    return null;
  }
}
