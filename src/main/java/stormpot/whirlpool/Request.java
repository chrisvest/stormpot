/*
 * Copyright 2011 Chris Vest
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
package stormpot.whirlpool;

import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.LockSupport;


class Request {
  boolean active;
  WSlot requestOp;
  WSlot response;
  Request next;
  int passCount;
  boolean hasTimeout;
  
  private final Thread thread;
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

  void await() {
    if (hasTimeout) {
      LockSupport.parkNanos(this, Whirlpool.PARK_TIME_NS);
    } else {
      LockSupport.park(this);
    }
  }

  void unpark() {
    LockSupport.unpark(thread);
  }

  public boolean deadlineIsPast(long now) {
    return hasTimeout && deadline < now;
  }

  public void deactivate() {
    active = false;
    unpark();
  }

  public boolean isInterrupted() {
    return thread.isInterrupted();
  }
}
