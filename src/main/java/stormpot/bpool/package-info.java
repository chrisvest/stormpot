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
/**
 * A {@link stormpot.Pool}, {@link stormpot.LifecycledPool} and
 * {@link stormpot.ResizablePool} implementation designed to be fast and
 * scalable in the face of concurrent and multi-threaded use, and high
 * contention.
 */
package stormpot.bpool;
