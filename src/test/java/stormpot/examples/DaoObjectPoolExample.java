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

public class DaoObjectPoolExample {
  static class MyDaoObject implements Poolable {
    private final Slot slot;
    private final Connection connection;
    
    private MyDaoObject(Slot slot, Connection connection) {
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
  
  static class MyDaoObjectAllocator implements Allocator<MyDaoObject> {
    private final DataSource dataSource;
    
    public MyDaoObjectAllocator(DataSource dataSource) {
      this.dataSource = dataSource;
    }

    public MyDaoObject allocate(Slot slot) throws Exception {
      return new MyDaoObject(slot, dataSource.getConnection());
    }

    public void deallocate(MyDaoObject poolable) throws Exception {
      poolable.close();
    }
  }
  
  static interface WithMyDaoObjectDo<T> {
    public T doWithDao(MyDaoObject dao);
  }
  
  static class MyDaoObjectPool {
    private final LifecycledPool<MyDaoObject> pool;
    
    public MyDaoObjectPool(DataSource dataSource) {
      MyDaoObjectAllocator allocator = new MyDaoObjectAllocator(dataSource);
      Config<MyDaoObject> config = new Config().setAllocator(allocator);
      pool = new QueuePool(config);
    }
    
    public <T> T doWithDao(WithMyDaoObjectDo<T> action)
    throws InterruptedException {
      MyDaoObject dao = pool.claim();
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
    MyDaoObjectPool pool = new MyDaoObjectPool(dataSource);
    String person = pool.doWithDao(new WithMyDaoObjectDo<String>() {
      public String doWithDao(MyDaoObject dao) {
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
