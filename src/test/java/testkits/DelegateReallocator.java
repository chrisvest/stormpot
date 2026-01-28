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
package testkits;

import stormpot.Poolable;
import stormpot.Reallocator;
import stormpot.Slot;

import java.util.concurrent.CompletionStage;

public class DelegateReallocator<T extends Poolable> extends DelegateAllocator<T> implements Reallocator<T> {
  public DelegateReallocator(Reallocator<T> delegate) {
    super(delegate);
  }

  @Override
  public T reallocate(Slot slot, T poolable) throws Exception {
    return ((Reallocator<T>) delegate).reallocate(slot, poolable);
  }

  @Override
  public CompletionStage<T> reallocateAsync(Slot slot, T poolable) {
    return ((Reallocator<T>) delegate).reallocateAsync(slot, poolable);
  }
}
