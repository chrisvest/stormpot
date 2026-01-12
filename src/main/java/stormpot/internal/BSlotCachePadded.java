/*
 * Copyright © Chris Vest (mr.chrisvest@gmail.com)
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

import stormpot.Poolable;

/**
 * A reference to a {@link BSlot}, with extra padding to keep the pointer on its own cache line.
 * @param <T> The concrete poolable type.
 */
@SuppressWarnings("unused")
public class BSlotCachePadded<T extends Poolable> extends BSlotCache<T> {
  private long p00;
  private long p01;
  private long p02;
  private long p03;
  private long p04;
  private long p05;
  private long p06;
  private long p07;
  private long p08;
  private long p09;
  private long p10;
  private long p11;
  private long p12;
  private long p13;

  /**
   * Create a new, empty instance.
   */
  public BSlotCachePadded() {
  }
}
