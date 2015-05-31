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
 * An interface for {@link Pool}s that are both
 * {@link LifecycledPool life-cycled} and {@link ResizablePool resizable}.
 * 
 * @author Chris Vest <mr.chrisvest@gmail.com>
 * @param <T> The type of {@link Poolable} contained in this pool.
 */
public interface LifecycledResizablePool<T extends Poolable>
    extends LifecycledPool<T>, ResizablePool<T> {
}
