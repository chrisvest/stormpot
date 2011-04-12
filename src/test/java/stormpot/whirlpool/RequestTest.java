package stormpot.whirlpool;

import static org.junit.Assert.*;

import org.junit.Test;

public class RequestTest {
  @Test public void
  getMustReturnRequestRequest() {
    assertNotNull(Request.get());
  }
  
  @Test public void
  getMustReturnExistingRequest() {
    Request r1 = Request.get();
    Request r2 = Request.get();
    assertTrue( r1 == r2);
  }
  // TODO request must be thread local
  // TODO get must return active request
  // TODO get must allocate new request if existing is inactive
}
