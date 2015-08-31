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

/**
 * = Stormpot
 *
 * Stormpot is a generic, thread-safe and fast object pooling library.
 *
 * The object pools themselves implement the {@link stormpot.Pool} interface.
 * The things you actually want to pool must all implement the
 * {@link stormpot.Poolable} interface, and you must also provide an
 * implementation of the {@link stormpot.Allocator} interface as a factory to
 * create your pooled objects.
 *
 * include::docs/usage.adoc[]
 *
 */
package stormpot;
