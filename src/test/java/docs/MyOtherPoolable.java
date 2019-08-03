/*
 * Copyright © 2011-2019 Chris Vest (mr.chrisvest@gmail.com)
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
package docs;

// tag::myotherpoolable[]
// MyOtherPoolable.java - explicit Poolable implementation
import stormpot.Poolable;
import stormpot.Slot;

public class MyOtherPoolable implements Poolable {
  private final Slot slot;
  public MyOtherPoolable(Slot slot) {
    this.slot = slot;
  }

  @Override
  public void release() {
    slot.release(this);
  }
}
// end::myotherpoolable[]
