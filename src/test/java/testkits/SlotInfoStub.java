/*
 * Copyright © 2011 Chris Vest (mr.chrisvest@gmail.com)
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

import stormpot.SlotInfo;

public class SlotInfoStub implements SlotInfo<GenericPoolable> {
  private long ageInMillis;
  private long stamp = 0;

  public SlotInfoStub() {
  }

  public SlotInfoStub(long ageInMillis) {
    this.ageInMillis = ageInMillis;
  }

  @Override
  public long getAgeMillis() {
    return ageInMillis;
  }

  public void setAgeInMillis(long ageInMillis) {
    this.ageInMillis = ageInMillis;
  }

  @Override
  public long getCreatedNanoTime() {
    return 0;
  }

  @Override
  public GenericPoolable getPoolable() {
    return null;
  }

  @Override
  public long getStamp() {
    return stamp;
  }

  @Override
  public void setStamp(long stamp) {
    this.stamp = stamp;
  }
}
