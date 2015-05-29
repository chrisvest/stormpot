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

public class MockSlotInfo implements SlotInfo<GenericPoolable> {
  private static int seed = 987623458;

  private static int xorShift(int seed) {
    seed ^= (seed << 6);
    seed ^= (seed >>> 21);
    return seed ^ (seed << 7);
  }

  private long ageInMillis;
  private long stamp = 0;

  public MockSlotInfo(long ageInMillis) {
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
  public long getClaimCount() {
    return 0;
  }

  @Override
  public GenericPoolable getPoolable() {
    return null;
  }

  @Override
  public int randomInt() {
    return seed = xorShift(seed);
  }

  @Override
  public long getStamp() {
    return stamp;
  }

  @Override
  public void setStamp(long stamp) {
    this.stamp = stamp;
  }

  public static MockSlotInfo mockSlotInfoWithAge(long ageInMillis) {
    return new MockSlotInfo(ageInMillis);
  }
}
