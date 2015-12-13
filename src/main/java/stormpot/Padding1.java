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

/**
 * This class, when extended, pads the fields of the subclass by 64 bytes,
 * assuming a 12 byte object header.
 * This is the same as the cache line size on most commonly used CPU
 * architectures.
 */
abstract class Padding1 {
  private int p0;
  private long p1, p2, p3, p4, p5, p6;
}
