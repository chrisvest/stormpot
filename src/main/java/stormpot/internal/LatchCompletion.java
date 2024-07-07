/*
 * Copyright © 2011-2024 Chris Vest (mr.chrisvest@gmail.com)
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package stormpot.internal;

import stormpot.Completion;
import stormpot.Timeout;

import java.util.Objects;
import java.util.concurrent.CountDownLatch;

public final class LatchCompletion implements Completion {
  private final CountDownLatch completionLatch;
  
  LatchCompletion(CountDownLatch completionLatch) {
    this.completionLatch = completionLatch;
  }

  @Override
  public boolean await(Timeout timeout) throws InterruptedException {
    Objects.requireNonNull(timeout, "Timeout cannot be null.");
    return completionLatch.await(timeout.getTimeout(), timeout.getUnit());
  }
}
