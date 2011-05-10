package stormpot.whirlpool;

import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.LockSupport;


class Request {
  boolean active;
  WSlot requestOp;
  WSlot response;
  Request next;
  int passCount;
  final Thread thread;
  
  private boolean hasTimeout;
  private long deadline;
  
  Request() {
    active = false;
    thread = Thread.currentThread();
  }

  void setTimeout(long timeout, TimeUnit unit) {
    deadline = System.currentTimeMillis() + unit.toMillis(timeout);
    hasTimeout = true;
  }

  void setNoTimeout() {
    deadline = 0;
    hasTimeout = false;
  }

  boolean await() {
    if (hasTimeout && System.currentTimeMillis() > deadline) {
      return false;
    }
    if (hasTimeout) {
      LockSupport.parkNanos(this, Whirlpool.PARK_TIME_NS);
    } else {
      LockSupport.park(this);
    }
    return true;
  }

  void unpark() {
    LockSupport.unpark(thread);
  }

  boolean blockedOnSelf() {
    return LockSupport.getBlocker(thread) == this;
  }
}
