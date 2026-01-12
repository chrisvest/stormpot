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
package stormpot.internal;

import java.io.Serial;

/**
 * Exception type used to mark poison conditions on slots or in the pool.
 * <p>
 * This exception type is suitable for use in constants.
 * Exception objects of this type do not include stack traces,
 * and do not allow suppressed exceptions to be added to them.
 */
public final class PoisonException extends Exception {
  @Serial
  private static final long serialVersionUID = 5196724916292026430L;

  /**
   * Create a new exception with the given message.
   * @param message The message of the exception.
   */
  public PoisonException(String message) {
    super(message, null, false, false);
  }
}
