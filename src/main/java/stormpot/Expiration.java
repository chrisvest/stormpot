/*
 * Copyright 2012 Chris Vest
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
 * The expiration is used to determine if a given slot has expired, or
 * otherwise become invalid.
 * @author Chris Vest &lt;mr.chrisvest@gmail.com&gt;
 */
public interface Expiration<T extends Poolable> {
  /**
   * Test whether the slot and poolable object, represented by the given
   * {@link SlotInfo} object, is still valid, or if the pool should
   * deallocate it and allocate a replacement.
   * <p>
   * If the method throws an exception, then that is taken to mean that the
   * slot is invalid. How pools otherwise handle the exception - if it will
   * bubble out, and if so, where - is implementation specific. For this
   * reason, it is generally advised that Expirations do not throw
   * exceptions.
   * @param info An informative representative of the slot being tested.
   * Never null.
   * @return <code>true</code> if the slot and poolable in question should be
   * deallocated, <code>false</code> if it is valid and elegible for claiming.
   */
  boolean hasExpired(SlotInfo<? extends T> info);
}
