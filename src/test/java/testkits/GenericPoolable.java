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
package testkits;

import stormpot.BasePoolable;
import stormpot.Slot;

public class GenericPoolable extends BasePoolable {
  @SuppressWarnings("WeakerAccess")
  public Thread lastReleaseBy; // readable in debuggers

  public GenericPoolable(Slot slot) {
    super(slot);
  }

  @Override
  public void release() {
    lastReleaseBy = Thread.currentThread();
    super.release();
  }

  public Slot getSlot() {
    return slot;
  }
}
