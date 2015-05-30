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

final class QueueFactory {
  interface Factory {
    <T> BlockingQueue<T> createUnboundedBlockingQueue();
  }

  static enum FactoryChoiceReason {
    DEFAULT_FIRST_CHOICE("The " + QUEUE_IMPL_FIRST_CHOICE + " was available on the classpath," +
            " and the " + QUEUE_IMPL + " system property did not specify any alternative" +
            " implementation to use."),
    SPECIFIED_CHOICE("The " + QUEUE_IMPL + " system property specified the queue implementation" +
            " to use, specifically " + System.getProperty(QUEUE_IMPL) + "."),
    DEFAULT_SECOND_CHOICE("The " + QUEUE_IMPL_FIRST_CHOICE + " was not available on the" +
            " classpath, and the " + QUEUE_IMPL + " system property did not specify any" +
            " alternative implementation to use, so " + QUEUE_IMPL_SECOND_CHOICE + " was used" +
            " as the default fallback."),
    DEFAULT_FALLBACK("")
    ;

    private final String description;

    FactoryChoiceReason(String description) {
      this.description = description;
    }

    public String toString() {
      return super.toString() + "(" + description + ")";
    }
  }

  private static final Factory factory;
  private static final FactoryChoiceReason reason;

  // stop checking line length
  private static final String QUEUE_IMPL = "stormpot.blocking.queue.impl";
  private static final String QUEUE_IMPL_FIRST_CHOICE = "java.util.concurrent.LinkedTransferQueue";
  private static final String QUEUE_IMPL_SECOND_CHOICE = LinkedBlockingQueue.class.getCanonicalName();
  // start checking line length

  static {
    Factory theFactory = null;
    FactoryChoiceReason theReason = FactoryChoiceReason.SPECIFIED_CHOICE;
    String queueType = System.getProperty(QUEUE_IMPL);

    if (queueType == null) {
      queueType = QUEUE_IMPL_FIRST_CHOICE;
      theReason = FactoryChoiceReason.DEFAULT_FIRST_CHOICE;
    }

    try {
      Class<?> queueClass = Class.forName(queueType);
      final Constructor<?> constructor = queueClass.getConstructor();
      BlockingQueue.class.cast(constructor.newInstance()); // Just making sure that it works...
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
    } catch (Exception e) {
      if (theReason == FactoryChoiceReason.SPECIFIED_CHOICE) {
        Exception exception = new Exception(
            "WARNING: The queue implementation specified by the " + QUEUE_IMPL + " system" +
            " property, specifically " + queueType + ", was determined to be unusable." +
            " Falling back to the " + QUEUE_IMPL_SECOND_CHOICE + " implementation.",
            e);
        exception.printStackTrace();
      }
    }

    if (theFactory == null) {
      theFactory = new Factory() {
        @Override
        public <T> BlockingQueue<T> createUnboundedBlockingQueue() {
          return new LinkedBlockingQueue<T>();
        }
      };
      theReason = theReason == FactoryChoiceReason.DEFAULT_FIRST_CHOICE?
          FactoryChoiceReason.DEFAULT_SECOND_CHOICE : FactoryChoiceReason.DEFAULT_FALLBACK;
    }

    factory = theFactory;
    reason = theReason;
  }

  static <T> BlockingQueue<T> createUnboundedBlockingQueue() {
    return factory.createUnboundedBlockingQueue();
  }

  static FactoryChoiceReason getQueueFactoryChoiceReason() {
    return reason;
  }
}
