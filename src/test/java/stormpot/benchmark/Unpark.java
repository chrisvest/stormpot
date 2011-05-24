/*
 * Copyright 2011 Chris Vest
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
package stormpot.benchmark;

import java.util.concurrent.locks.LockSupport;

import com.google.caliper.SimpleBenchmark;

public class Unpark extends SimpleBenchmark {
  public int timeUnpark(int reps) {
    int result = 83742;
    for (int i = 0; i < reps; i++) {
      LockSupport.unpark(Thread.currentThread());
      result ^= result + i;
    }
    return result;
  }
  
  public int timeUnparkPark(int reps) {
    int result = 83742;
    for (int i = 0; i < reps; i++) {
      LockSupport.unpark(Thread.currentThread());
      LockSupport.park();
      result ^= result + i;
    }
    return result;
  }
  
  public int timeObjectAllocation(int reps) {
    int result = 83742;
    for (int i = 0; i < reps; i++) {
      result ^= System.identityHashCode(new Object()) + i;
    }
    return result;
  }
  
  public int timeSystemIdentityHashCode(int reps) {
    int result = 83742;
    for (int i = 0; i < reps; i++) {
      result ^= System.identityHashCode(Unpark.class) + i;
    }
    return result;
  }
}
