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
package stormpot.simulations;

import stormpot.*;

import java.util.concurrent.TimeUnit;

/**
 * A simulation that explores how the pool reacts to periods of low activity.
 */
@Sim.Simulation(pools = {BlazePool.class}, measurementTime = 11, output = Sim.Output.summary)
public class VariableTrafficSim extends Sim {
  private static final Timeout timeout = new Timeout(1, TimeUnit.MINUTES);
  private volatile long startTime;

  @Conf(Param.expiration)
  public TimeExpiration<Poolable> expiration =
      new TimeExpiration<>(6, TimeUnit.SECONDS);

  @Conf(Param.backgroundExpirationEnabled)
  public boolean[] backgroundExpiration = {true, false};

  // We have a number of agents...
  @Agents({@Agent, @Agent, @Agent, @Agent})
  public void claimRelease(Pool<Poolable> pool) throws InterruptedException {
    if (startTime == 0) {
      startTime = System.currentTimeMillis();
    }
    pool.claim(timeout).release();
  }

  // ... that for an initial period are very busy ...
  @AgentPause(unit = TimeUnit.MILLISECONDS)
  public long pause() {
    long now = System.currentTimeMillis() - startTime;
    if (now < 5000) {
      return 100; // short pause for the first 5 seconds
    }

    // ... then suddenly they sleep for 5 seconds ...
    if (now < 6000) {
      // ... while they sleep, all the objects will expire at the 6 second point ...
      return 5000;
      // ... this leaves 3 seconds for any background allocation to replace the expired objects ...
    }

    // ... and then they suddenly become very active again ...
    return 100;
  }

  // ... if no expiration checks happen in the background ...
  @AllocationCost
  public long allocationCost() {
    // ... then this high allocation cost will hit the agents hard, when they wake up.
    return 500;
    // Each object costs 500 milliseconds to allocate, so allocating enough objects to
    // satisfy the agents will take 2 seconds.
  }
}
/*
Example run (histogram units in milliseconds):

Simulating VariableTrafficSim {
	size = 10
	expiration = TimeExpiration(6 SECONDS)
	backgroundExpirationEnabled = true
	preciseLeakDetectionEnabled = true
	metricsRecorder = null
	threadFactory = stormpot.StormpotThreadFactory@961e946
} for BlazePool
Latency results sum:
       Value     Percentile TotalCount 1/(1-Percentile)

       0.008 0.000000000000          1           1.00
       0.033 0.500000000000        124           2.00
       0.054 0.750000000000        182           4.00
       0.063 0.875000000000        209           8.00
       0.071 0.937500000000        223          16.00
       0.080 0.968750000000        229          32.00
       0.104 0.984375000000        233          64.00
       0.105 0.992187500000        235         128.00
       0.153 0.996093750000        236         256.00
       0.153 1.000000000000        236
#[Mean    =        0.043, StdDeviation   =        0.018]
#[Max     =        0.153, Total count    =          236]
#[Buckets =           20, SubBuckets     =         2048]

Simulating VariableTrafficSim {
	size = 10
	expiration = TimeExpiration(6 SECONDS)
	backgroundExpirationEnabled = false
	preciseLeakDetectionEnabled = true
	metricsRecorder = null
	threadFactory = stormpot.StormpotThreadFactory@961e946
} for BlazePool
Latency results sum:
       Value     Percentile TotalCount 1/(1-Percentile)

       0.016 0.000000000000          2           1.00
       0.033 0.500000000000        117           2.00
       0.055 0.750000000000        170           4.00
       0.065 0.875000000000        196           8.00
       0.101 0.937500000000        210          16.00
       0.196 0.968750000000        217          32.00
     488.191 0.984375000000        221          64.00
     504.831 0.992187500000        224         128.00
     504.831 1.000000000000        224
#[Mean    =        8.985, StdDeviation   =       66.289]
#[Max     =      504.831, Total count    =          224]
#[Buckets =           20, SubBuckets     =         2048]

Done, 63.866 seconds.
 */
