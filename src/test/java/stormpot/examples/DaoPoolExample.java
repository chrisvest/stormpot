package stormpot.examples;

import java.sql.Connection;
import java.sql.SQLException;

import javax.sql.DataSource;

import stormpot.Allocator;
import stormpot.Config;
import stormpot.LifecycledPool;
import stormpot.Poolable;
import stormpot.Slot;
import stormpot.qpool.QueuePool;

public class DaoPoolExample {
  static class MyDao implements Poolable {
    private final Slot slot;
    private final Connection connection;
    
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
      return new MyDao(slot, dataSource.getConnection());
    }

    public void deallocate(MyDao poolable) throws Exception {
      poolable.close();
    }
  }
  
  static interface WithMyDaoDo<T> {
    public T doWithDao(MyDao dao);
  }
  
  static class MyDaoPool {
    private final LifecycledPool<MyDao> pool;
    
    public MyDaoPool(DataSource dataSource) {
      MyDaoAllocator allocator = new MyDaoAllocator(dataSource);
      Config<MyDao> config = new Config().setAllocator(allocator);
      pool = new QueuePool(config);
    }
    
    public <T> T doWithDao(WithMyDaoDo<T> action)
    throws InterruptedException {
      MyDao dao = pool.claim();
      try {
        return action.doWithDao(dao);
      } finally {
        dao.release();
      }
    }

    public void close() throws InterruptedException {
      pool.shutdown().await();
    }
  }
  
  public static void main(String[] args) throws InterruptedException {
    DataSource dataSource = configureDataSource();
    MyDaoPool pool = new MyDaoPool(dataSource);
    String person = pool.doWithDao(new WithMyDaoDo<String>() {
      public String doWithDao(MyDao dao) {
        return dao.getFirstName();
      }
    });
    System.out.println("Hello there, " + person + "!");
    pool.close();
  }

  private static DataSource configureDataSource() {
    // Stub: configure and return some kind of DataSource object.
    // Allocation will fail with a NullPointerException until this method
    // get a proper implementation.
    return null;
  }
}
