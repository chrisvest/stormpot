package stormpot;

import static org.junit.Assert.*;
import static org.hamcrest.Matchers.*;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.LockSupport;

import org.junit.Before;
import org.junit.Test;
import org.junit.experimental.theories.DataPoint;
import org.junit.experimental.theories.Theories;
import org.junit.experimental.theories.Theory;
import org.junit.runner.RunWith;

import stormpot.basicpool.BasicPoolFixture;
import stormpot.qpool.QPoolFixture;

@RunWith(Theories.class)
public class ResizablePoolTest {
  private static final Timeout longTimeout = new Timeout(1, TimeUnit.SECONDS);
  
  @DataPoint public static PoolFixture basicPool = new BasicPoolFixture();
  @DataPoint public static PoolFixture queuePool = new QPoolFixture();

  private CountingAllocator allocator;
  private Config<GenericPoolable> config;
  
  @Before public void
  setUp() {
    allocator = new CountingAllocator();
    config = new Config<GenericPoolable>().setAllocator(allocator).setSize(1);
  }

  private ResizablePool<GenericPoolable> resizable(PoolFixture fixture) {
    return (ResizablePool<GenericPoolable>) fixture.initPool(config);
  }
  
  @Theory public void
  mustImplementResizablPool(PoolFixture fixture) {
    assertThat(fixture.initPool(config), instanceOf(ResizablePool.class));
  }
  
  @Theory public void
  targetSizeMustBeConfiguredSizeByDefault(PoolFixture fixture) {
    config.setSize(23);
    ResizablePool<GenericPoolable> pool = resizable(fixture);
    assertThat(pool.getTargetSize(), is(23));
  }
  
  @Theory public void
  getTargetSizeMustReturnLastSetTargetSize(PoolFixture fixture) {
    ResizablePool<GenericPoolable> pool = resizable(fixture);
    pool.setTargetSize(3);
    assertThat(pool.getTargetSize(), is(3));
  }
  
  @Test(timeout = 300)
  @Theory public void
  increasingSizeMustAllowMoreAllocations(PoolFixture fixture) throws Exception {
    ResizablePool<GenericPoolable> pool = resizable(fixture);
    pool.claim(longTimeout); // depleted
    pool.setTargetSize(2);
    // now this mustn't block:
    pool.claim(longTimeout);
  }
  
  @Test(timeout = 300)
  @Theory public void
  decreasingSizeMustEventuallyDeallocateSurplusObjects(PoolFixture fixture) throws Exception {
    int startingSize = 5;
    int newSize = 1;
    final Thread main = Thread.currentThread();
    CountingAllocator allocator = new CountingAllocator() {
      @Override
      public GenericPoolable allocate(Slot slot) throws Exception {
        try {
          return super.allocate(slot);
        } finally {
          LockSupport.unpark(main);
        }
      }

      @Override
      public void deallocate(GenericPoolable poolable) throws Exception {
        try {
          super.deallocate(poolable);
        } finally {
          LockSupport.unpark(main);
        }
      }
    };
    config.setSize(startingSize);
    config.setAllocator(allocator);
    ResizablePool<GenericPoolable> pool = resizable(fixture);
    List<GenericPoolable> objs = new ArrayList<GenericPoolable>();
    
    while (allocator.allocations() != startingSize) {
      objs.add(pool.claim(longTimeout)); // force the pool to do work
      LockSupport.parkNanos(10000000); // 10 millis
    }
    pool.setTargetSize(newSize);
    while (allocator.deallocations() != startingSize - newSize) {
      if (objs.size() > 0) {
        objs.remove(0).release(); // give the pool objects to deallocate
      } else {
        pool.claim(longTimeout).release(); // prod it & poke it
      }
      LockSupport.parkNanos(10000000); // 10 millis
    }
    assertThat(
        allocator.allocations() - allocator.deallocations(), is(newSize));
  }
}
