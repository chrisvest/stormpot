/*
 * Copyright (C) 2011-2014 Chris Vest (mr.chrisvest@gmail.com)
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *         http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package stormpot;

import java.util.Queue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.Callable;

final class PrimedBlockingQueue<T> extends LinkedBlockingQueue<T> {
  private static final long serialVersionUID = -2138789305960877995L;
  
  private final Queue<Callable<T>> calls;
  private T lastValue;

  PrimedBlockingQueue(Queue<Callable<T>> calls) {
    this.calls = calls;
  }

  public T poll(long timeout, TimeUnit unit)
      throws InterruptedException {
    return poll();
  }
  
  public T poll() {
    Callable<T> callable = calls.poll();
    if (callable != null) {
      try {
        lastValue = callable.call();
      } catch (RuntimeException runtimeException) {
        throw runtimeException;
      } catch (Exception exception) {
        throw new AssertionError(exception);
      }
    }
    return lastValue;
  }

  public boolean offer(T e) {
    return false;
  }
}
