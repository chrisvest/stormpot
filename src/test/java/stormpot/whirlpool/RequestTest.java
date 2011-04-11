package stormpot.whirlpool;

import static org.junit.Assert.*;

import org.junit.Test;

public class RequestTest {
  @Test public void
  getMustReturnRequestObject() {
    assertNotNull(Request.get());
  }
  // TODO get must return existing active object
  // TODO get must allocate new object if existing is inactive
}
