/*
 * Copyright 2011 Chris Vest
 * 
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 * 
 *      http://www.apache.org/licenses/LICENSE-2.0
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
 * The contract of the Poolable interface is, that when {@link #release()
 * release} is called on the Poolable, it must in turn call
 * {@link Slot#release(Poolable) release} on the specific Slot object that it
 * was allocated with, giving itself as the Poolable parameter.
 * <p>
 * The simplest possible correct implementation of the Poolable interface looks
 * like this:
 * <pre><code> public class GenericPoolable implements Poolable {
 *   private final Slot slot;
 *   public GenericPoolable(Slot slot) {
 *     this.slot = slot;
 *   }
 *   
 *   public void release() {
 *     slot.release(this);
 *   }
 * }</code></pre>
 * Pools in turn guarantee these memory effects:
 * <ul>
 * <li>The {@link Poolable#release() release} of an object happens-before
 * any subsequent claim of that object,
 * <li>The {@link Allocator#allocate(Slot) allocation} of an object
 * happens-before any claim of that object, and,
 * <li>The the claim of an object happens-before any subsequent release of
 * that object.
 * </ul>
 * @author Chris Vest &lt;mr.chrisvest@gmail.com&gt;
 *
 */
public interface Poolable {
  /**
   * Release this Poolable object back into the pool from where it came,
   * so that others can claim it, or the pool deallocate it if it has
   * expired.
   * <p>
   * A call to this method MUST delegate to a call to
   * {@link Slot#release(Poolable) release} on the Slot object for which this
   * Poolable was allocated, giving itself as the Poolable parameter.
   */
  void release();
}
