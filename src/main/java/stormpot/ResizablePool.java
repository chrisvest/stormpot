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
 * ResizablePools are pools that can change their size after they have been
 * created.
 *
 * All pools are configured with a certain size, the number of objects that the
 * pool will have allocated at any one time, and resizable pools can change
 * this number on the fly. The change does not take effect immediately, but
 * instead moves the "goal post" and leaves the pool to move towards it at its
 * own pace.
 *
 * No guarantees can be made about when the pool will actually reach the target
 * size, because it might depend on how long it takes for a certain number of
 * objects to be released back into the pool.
 * @author Chris Vest <mr.chrisvest@gmail.com>
 *
 * @param <T> The type of {@link Poolable} contained in this pool.
 */
public interface ResizablePool<T extends Poolable> extends Pool<T> {
  /**
   * Set the target size for this pool. The pool will strive to keep this many
   * objects allocated at any one time.
   *
   * If the new target size is greater than the old one, the pool will allocate
   * more objects until it reaches the target size. If, on the other hand, the
   * new target size is less than the old one, the pool will deallocate more
   * and allocate less, until the new target size is reached.
   *
   * No guarantees are made about when the pool actually reaches the target
   * size. In fact, it may never happen as the target size can be changed as
   * often as one sees fit.
   *
   * Pools that do not support a size less than 1 (which would deviate from the
   * standard configuration space) will throw an
   * {@link IllegalArgumentException} if passed 0 or less.
   * @param size The new target size of the pool
   */
  void setTargetSize(int size);

  /**
   * Get the currently configured target size of the pool. Note that this is
   * _not_ the number of objects currently allocated by the pool - only
   * the number of allocations the pool strives to keep alive.
   * @return The current target size of this pool.
   */
  int getTargetSize();
}
