/*
 * Copyright © 2011-2024 Chris Vest (mr.chrisvest@gmail.com)
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
package stormpot.internal;

/**
 * A holder of permissions, deciding what {@link stormpot.PoolBuilder} settings are allowed to be changed.
 * @param setAllocator {@code true} if the allocator can be changed, otherwise {@code false}.
 * @param setSize {@code true} if the size can be changed, otherwise {@code false}.
 * @param setExpiration {@code true} if the expiration can be changed, otherwise {@code false}.
 * @param setThreadFactory {@code true} if the thread factory can be changed, otherwise {@code false}.
 * @param setBackgroundExpiration {@code true} if background expiration checking can be enabled or disabled,
 *                                            otherwise {@code false}.
 */
public record PoolBuilderPermissions(
        boolean setAllocator,
        boolean setSize,
        boolean setExpiration,
        boolean setThreadFactory,
        boolean setBackgroundExpiration) {
}
