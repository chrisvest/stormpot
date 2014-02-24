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

import java.lang.reflect.Constructor;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;

class QueueFactory {
  interface Factory {
    <T> BlockingQueue<T> createUnboundedBlockingQueue();
  }

  private static final Factory factory;

  static {
    Factory theFactory = null;
    String queueClass = System.getProperty(
        "stormpot.blocking.queue.impl",
        "java.util.concurrent.LinkedTransferQueue");
    try {
      Class<?> transferqueueClass = Class.forName(queueClass);
      final Constructor<?> constructor = transferqueueClass.getConstructor();
      constructor.newInstance(); // Just making sure that it works...
      theFactory = new Factory() {
        @SuppressWarnings("unchecked")
        @Override
        public <T> BlockingQueue<T> createUnboundedBlockingQueue() {
          try {
            return (BlockingQueue<T>) constructor.newInstance();
          } catch (Exception e) {
            Exception exception = new Exception(
                "WARNING: Expected class to be constructable: " + constructor,
                e);
            exception.printStackTrace();
          }
          return new LinkedBlockingQueue<T>();
        }
      };
    } catch (Exception ignore) {
    }
    if (theFactory == null) {
      theFactory = new Factory() {
        @Override
        public <T> BlockingQueue<T> createUnboundedBlockingQueue() {
          return new LinkedBlockingQueue<T>();
        }
      };
    }
    factory = theFactory;
  }

  static <T> BlockingQueue<T> createUnboundedBlockingQueue() {
    return factory.createUnboundedBlockingQueue();
  }
}
