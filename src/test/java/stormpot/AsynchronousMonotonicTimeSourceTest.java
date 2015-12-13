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

import org.junit.Before;
import org.junit.Test;

import static org.hamcrest.Matchers.lessThanOrEqualTo;
import static org.junit.Assert.assertThat;

public class AsynchronousMonotonicTimeSourceTest {
  private AsynchronousMonotonicTimeSource clock;

  @Before
  public void setUp() {
    clock = new AsynchronousMonotonicTimeSource();
  }

  @Test public void
  mustProvideTimeWhenAsynchronisityIsOff() throws Exception {
    long a = System.nanoTime();
    sleep();
    long b = clock.nanoTime();
    long c = clock.nanoTime();
    long d = System.nanoTime();
    assertThat(a, lessThanOrEqualTo(b));
    assertThat(b, lessThanOrEqualTo(c));
    assertThat(c, lessThanOrEqualTo(d));
  }

  private void sleep() throws InterruptedException {
    Thread.sleep(20);
  }

  @Test public void
  mustHaveNonBogusTimeAfterTurningAsyncOn() throws Exception {
    long a = System.nanoTime();
    sleep();
    clock.setAsync(true);
    long b = clock.nanoTime();
    assertThat(a, lessThanOrEqualTo(b));
  }

  @Test public void
  asyncTimeMustChangeWhenUpdated() throws Exception {
    clock.setAsync(true);
    long a = clock.nanoTime();
    sleep();
    clock.updateTime();
    long b = clock.nanoTime();
    assertThat(a, lessThanOrEqualTo(b));
  }
}
