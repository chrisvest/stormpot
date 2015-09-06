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

import sun.misc.Unsafe;

import java.lang.reflect.Field;
import java.security.AccessController;
import java.security.PrivilegedActionException;
import java.security.PrivilegedExceptionAction;

final class UnsafeUtil {
  private static final Unsafe unsafe = getUnsafe();

  private static Unsafe getUnsafe() {
    try {
      return AccessController.doPrivileged(
        (PrivilegedExceptionAction<Unsafe>) () -> {
          Class<Unsafe> unsafeClass = Unsafe.class;
          for (Field field : unsafeClass.getDeclaredFields()) {
            if (unsafeClass.isAssignableFrom(field.getType())) {
              field.setAccessible(true);
              Object obj = field.get(null);
              return unsafeClass.cast(obj);
            }
          }
          return null;
        });
    } catch (PrivilegedActionException e) {
      return null;
    }
  }

  static boolean hasUnsafe() {
    return unsafe != null;
  }

  static long objectFieldOffset(Class<?> type, String fieldName) {
    if (hasUnsafe()) {
      try {
        Field field = type.getDeclaredField(fieldName);
        return unsafe.objectFieldOffset(field);
      } catch (NoSuchFieldException e) {
        throw new Error(
            "Intrinsic error: objectFieldOffset for " + fieldName, e);
      }
    }
    return 0;
  }

  static boolean compareAndSwapInt(
      Object obj, long offset, int expected, int update) {
    return unsafe.compareAndSwapInt(obj, offset, expected, update);
  }

  static void putOrderedInt(Object obj, long offset, int update) {
    unsafe.putOrderedInt(obj, offset, update);
  }
}
