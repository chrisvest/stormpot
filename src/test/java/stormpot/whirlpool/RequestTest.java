package stormpot.whirlpool;

import static org.junit.Assert.*;

import static stormpot.UnitKit.*;

import java.util.concurrent.Callable;
import java.util.concurrent.atomic.AtomicReference;

import org.junit.Before;
import org.junit.Test;

public class RequestTest {
  @Before public void
  setUp() {
    Request.clear();
  }
  
  @Test public void
  getMustReturnRequestRequest() {
    assertNotNull(Request.get());
  }
  
  @Test public void
  getMustReturnExistingRequest() {
    Request r1 = Request.get();
    Request r2 = Request.get();
    assertTrue(r1 == r2);
  }
  
  @Test(timeout = 300) public void
  requestMustBeThreadLocal() throws Exception {
    Request fromThisThread = Request.get();
    AtomicReference<Request> fromOtherThread = new AtomicReference<Request>();
    fork($getRequestInto(fromOtherThread)).join();
    assertTrue(fromThisThread != fromOtherThread.get());
  }

  private Callable $getRequestInto(final AtomicReference<Request> fromOtherThread) {
    return new Callable() {
      public Object call() throws Exception {
        fromOtherThread.set(Request.get());
        return null;
      }
    };
  }
  
  @Test public void
  getMustReturnActiveRequest() {
    assertTrue(Request.get().active());
  }
  
  @Test public void
  clearMustResetAssignedRequest() {
    Request r1 = Request.get();
    Request.clear();
    Request r2 = Request.get();
    assertTrue(r1 != r2);
  }
  
  @Test public void
  getMustAllocateNewRequestIfExistingIsInactive() {
    Request r1 = Request.get();
    r1.deactivate();
    Request r2 = Request.get();
    assertTrue(r1 != r2);
  }
}
