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
package stormpot.tests;

import java.util.concurrent.Flow;

import static org.junit.jupiter.api.Assertions.fail;

public class MySubscriber implements Flow.Subscriber<Void> {
  final Runnable onComplete;

  public MySubscriber(Runnable onComplete) {
    this.onComplete = onComplete;
  }

  @Override
  public void onSubscribe(Flow.Subscription subscription) {
    subscription.request(1);
  }

  @Override
  public void onNext(Void item) {
    fail("No item expected: " + item);
  }

  @Override
  public void onError(Throwable throwable) {
    fail("No error expecte", throwable);
  }

  @Override
  public void onComplete() {
    onComplete.run();
  }
}
