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

import org.junit.Test;

import static org.hamcrest.Matchers.is;
import static org.junit.Assert.*;

public class UnsafeUtilTest {
  private static long xFieldOffset =
      UnsafeUtil.objectFieldOffset(UnsafeUtilTest.class, "x");

  @SuppressWarnings("unused")
  private volatile int x;

  @Test public void
  unsafeMustBeAvailable() {
    assertTrue(UnsafeUtil.hasUnsafe());
  }

  @Test public void
  putOrderedIntMustBeImmediatelyObservableInSameThread() {
    UnsafeUtil.putOrderedInt(this, xFieldOffset, 42);

    assertThat(x, is(42));
  }

  @Test public void
  compareAndSwapIntMustReturnTrueWhenUpdateSucceeds() {
    boolean success = UnsafeUtil.compareAndSwapInt(this, xFieldOffset, 0, 42);

    assertTrue(success);
    assertThat(x, is(42));
  }

  @Test public void
  compareAndSwapIntMustReturnFalseWhenUpdateFails() {
    boolean success = UnsafeUtil.compareAndSwapInt(this, xFieldOffset, 42, 1337);

    assertFalse(success);
    assertThat(x, is(0));
  }
}
