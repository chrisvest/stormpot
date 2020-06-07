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

/**
 * A reference to a pooled object.
 */
public class Pooled<T> extends BasePoolable implements Poolable, AutoCloseable {
  /**
   * The object managed by this pooled instance.
   */
  public final T object;

  /**
   * Create a pooled object for the given slot and object to be pooled.
   *
   * @param slot The slot an object is being allocated for.
   * @param object The object this pooled instance represents.
   */
  public Pooled(Slot slot, T object) {
    super(slot);
    this.object = object;
  }

  /**
   * `Pooled` implements {@link AutoCloseable} as a convenient way to release
   * claimed objects back to the pool, using the try-with-resources syntax.
   */
  @Override
  public void close() {
    release();
  }

  @Override
  public String toString() {
    return "Pooled[" + object + "]";
  }
}
