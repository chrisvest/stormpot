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
 * A general purpose {@link stormpot.LifecycledResizablePool} implementation
 * designed to be fast and scalable in the common multi-threaded server-side
 * case.
 * <p>
 * BlazePool optimises for the case where the same threads need to claim and
 * release objects over and over again. On the other hand, if the releasing
 * thread tends to differ from the claiming thread, then the major optimisation
 * in BlazePool is defeated, and performance regresses to a slow-path that is
 * a tad slower than {@link stormpot.qpool.QueuePool}.
 */
package stormpot.bpool;
