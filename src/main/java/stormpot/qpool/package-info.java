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
 * based on a queueing design.
 * <p>
 * The design is simple and straight forward, and exhibits a reasonable
 * base-line performance in all cases. If, however, the same threads are going
 * to claim and release objects from the pool over and over again — for
 * instance in the case of a typical Java web application — then
 * {@link stormpot.bpool.BlazePool} is likely going to yield better
 * performance.
 */
package stormpot.qpool;
