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
  private static final Timeout shortTimeout = new Timeout(1, TimeUnit.MILLISECONDS);
  
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
  decreasingSizeMustEventuallyDeallocateSurplusObjects(PoolFixture fixture)
      throws Exception {
    int startingSize = 5;
    int newSize = 1;
    final Thread main = Thread.currentThread();
    CountingAllocator allocator = new UnparkingCountingAllocator(main);
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
  
  @Test(timeout = 300)
  @Theory public void
  mustNotReallocateWhenReleasingExpiredObjectsIntoShrunkPool(PoolFixture fixture)
      throws Exception {
    int startingSize = 5;
    int newSize = 1;
    CountingAllocator allocator = new CountingAllocator();
    config.setTTL(1, TimeUnit.MILLISECONDS).setAllocator(allocator);
    config.setSize(startingSize);
    ResizablePool<GenericPoolable> pool = resizable(fixture);
    List<GenericPoolable> objs = new ArrayList<GenericPoolable>();
    while (allocator.allocations() < startingSize) {
      objs.add(pool.claim(longTimeout));
    }
    UnitKit.spinwait(2); // wait for the objects to expire
    pool.setTargetSize(newSize);
    for (int i = 0; i < startingSize - newSize; i++) {
      // release the surplus expired objects back into the pool
      objs.remove(0).release();
    }
    // now the released objects should not cause reallocations, so claim
    // returns null (it's still depleted) and allocation count stays put
    assertThat(pool.claim(shortTimeout), nullValue());
    assertThat(allocator.allocations(), is(startingSize));
  }
}
