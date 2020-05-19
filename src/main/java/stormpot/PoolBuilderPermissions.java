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
package stormpot;

class PoolBuilderPermissions {
  final boolean setAllocator;
  final boolean setSize;
  final boolean setExpiration;
  final boolean setThreadFactory;
  final boolean setBackgroundExpiration;

  PoolBuilderPermissions(
      boolean setAllocator,
      boolean setSize,
      boolean setExpiration,
      boolean setThreadFactory,
      boolean setBackgroundExpiration) {
    this.setAllocator = setAllocator;
    this.setSize = setSize;
    this.setExpiration = setExpiration;
    this.setThreadFactory = setThreadFactory;
    this.setBackgroundExpiration = setBackgroundExpiration;
  }
}
