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
package stormpot;

/**
 * Objects contained in a {@link Pool} must implement the Poolable interface
 * and adhere to its contract.
 * <p>
 * Pools call {@link Allocator#allocate(Slot) allocate} on Allocators with the
 * specific {@link Slot} that they want a Poolable allocated for. The Slot
 * represents a location in the pool, that can fit an object and make it
 * available for others to claim.
 * <p>
 * The contract of the Poolable interface is, that when {@link #release()} is
 * called on the Poolable, it must in turn call {@link Slot#release(Poolable)}
 * on the specific Slot object that it was allocated with, giving itself as the
 * Poolable parameter.
 * <p>
 * A simple correct implementation of the Poolable interface looks like this:
 * {@snippet class=Examples region=poolableGenericExample}
 *
 * This can be shortened further by extending the {@link BasePoolable} class:
 * {@snippet class=Examples region=poolableBaseExample}
 *
 * It is also possible to directly use the {@link Pooled} implementation, which
 * implements both {@code Poolable} and {@link AutoCloseable}, and holds a
 * reference to the actual object being pooled.
 * <p>
 * Memory effects: The release of an object happens-before it is claimed
 * by another thread, or deallocated.
 * 
 * @author Chris Vest
 * @see BasePoolable
 * @see Pooled
 */
public interface Poolable {
  /**
   * Release this Poolable object back into the pool from where it came,
   * so that others can claim it, or the pool deallocate it if it has
   * expired.
   * <p>
   * A call to this method MUST delegate to a call to
   * {@link Slot#release(Poolable)} on the Slot object for which this
   * Poolable was allocated, giving itself as the Poolable parameter.
   * <p>
   * A Poolable can be released by a thread other than the one that claimed it.
   * It can not, however, be released more than once per claim. The feature of
   * permitting releases from threads other than the claiming thread might be
   * useful in message-passing architectures and the like, but it is not
   * generally recommendable because it makes it more complicated to keep track
   * of object life-cycles.
   * <p>
   * Great care must be taken, to ensure that Poolables are not used after they
   * are released!
   */
  void release();
}
