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

import stormpot.Allocator;
import stormpot.Poolable;
import stormpot.Slot;

import java.util.concurrent.CompletionStage;

import static java.util.Objects.requireNonNull;

public class DelegateAllocator<T extends Poolable> implements Allocator<T> {
  protected final Allocator<T> delegate;

  public DelegateAllocator(Allocator<T> delegate) {
    this.delegate = requireNonNull(delegate);
  }

  @Override
  public T allocate(Slot slot) throws Exception {
    return delegate.allocate(slot);
  }

  @Override
  public CompletionStage<T> allocateAsync(Slot slot) {
    return delegate.allocateAsync(slot);
  }

  @Override
  public void deallocate(T poolable) throws Exception {
    delegate.deallocate(poolable);
  }

  @Override
  public CompletionStage<Void> deallocateAsync(T poolable) {
    return delegate.deallocateAsync(poolable);
  }
}
