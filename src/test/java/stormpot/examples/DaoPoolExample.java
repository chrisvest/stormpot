/*
 * Copyright 2012 Chris Vest
 * 
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 * 
 *      http://www.apache.org/licenses/LICENSE-2.0
 * 
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package stormpot.examples;

import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.concurrent.TimeUnit;

import javax.sql.DataSource;

import stormpot.Allocator;
import stormpot.Config;
import stormpot.Expiration;
import stormpot.LifecycledPool;
import stormpot.Poolable;
import stormpot.Slot;
import stormpot.SlotInfo;
import stormpot.Timeout;
import stormpot.qpool.QueuePool;

public class DaoPoolExample {
  static class MyDao implements Poolable {
    final Slot slot;
    final Connection connection;
    
    private MyDao(Slot slot, Connection connection) {
      this.slot = slot;
      this.connection = connection;
    }

    public void release() {
      slot.release(this);
    }

    private void close() throws SQLException {
      connection.close();
    }
    
    // ...
    // public DAO-type methods
    // ...
    
    public String getFirstName() {
      // Stub: get the name from the database using the connection.
      // But for now, just always return "freddy"
      return "freddy";
    }
  }
  
  static class MyDaoAllocator implements Allocator<MyDao> {
    private final DataSource dataSource;
    
    public MyDaoAllocator(DataSource dataSource) {
      this.dataSource = dataSource;
    }

    public MyDao allocate(Slot slot) throws Exception {
      synchronized (dataSource) {
        return new MyDao(slot, dataSource.getConnection());
      }
    }

    public void deallocate(MyDao poolable) throws Exception {
      poolable.close();
    }
  }
  
  static class TestQueryExpiration implements Expiration<MyDao> {
    @Override
    public boolean hasExpired(SlotInfo<? extends MyDao> info) {
      MyDao dao = info.getPoolable();
      Connection con = dao.connection;
      Statement stmt = null;
      synchronized (con) {
        try {
          try {
            stmt = con.createStatement();
            stmt.execute("select 1 from dual;");
          } finally {
            if (stmt != null) {
              stmt.close();
            }
          }
        } catch (SQLException e) {
          return true;
        }
        return false;
      }
    }
  }
  
  static class MyDaoPool {
    private final LifecycledPool<MyDao> pool;
    
    public MyDaoPool(DataSource dataSource) {
      MyDaoAllocator allocator = new MyDaoAllocator(dataSource);
      Config<MyDao> config = new Config<MyDao>().setAllocator(allocator);
      config.setExpiration(new TestQueryExpiration());
      pool = new QueuePool<MyDao>(config);
    }

    public void close() throws InterruptedException {
      pool.shutdown().await(new Timeout(1, TimeUnit.MINUTES));
    }
    
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
  
  static interface WithMyDaoDo<T> {
    public T doWithDao(MyDao dao);
  }
  
  public static void main(String[] args) throws InterruptedException {
    DataSource dataSource = configureDataSource();
    MyDaoPool pool = new MyDaoPool(dataSource);
    try {
      String person = pool.doWithDao(new WithMyDaoDo<String>() {
        public String doWithDao(MyDao dao) {
          return dao.getFirstName();
        }
      });
      System.out.println("Hello there, " + person + "!");
    } finally {
      pool.close();
    }
  }

  private static DataSource configureDataSource() {
    // Stub: configure and return some kind of DataSource object.
    // Allocation will fail with a NullPointerException until this method
    // get a proper implementation.
    return null;
  }
}
