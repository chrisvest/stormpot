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
 * This simulation examines the difference that using TimeSpreadExpiration,
 * instead of TimeExpiration, can have on latency.
 */
@Sim.Simulation(pools = {BlazePool.class}, measurementTime = 60, output = Sim.Output.summary)
public class TimeSpreadExpirationSim extends Sim {
  @SuppressWarnings("rawtypes") // Not possible to have a generic array. It might be possible to make @Conf work with a
                                // java.util.Set instead, but that's probably not worth the effort.
  @Conf(Param.expiration)
  public Expiration[] expirations = {
      new TimeExpiration<>(2, TimeUnit.SECONDS),
      new TimeSpreadExpiration<>(1, 2, TimeUnit.SECONDS)
  };

  @Agents({@Agent, @Agent, @Agent, @Agent, @Agent, @Agent, @Agent})
  public void claimRelease(Pool<GenericPoolable> pool)
      throws InterruptedException {
    pool.claim(new Timeout(1, TimeUnit.MINUTES)).release();
  }

  @AgentPause
  public long pause() {
    return 100;
  }

  @AllocationCost
  public long allocationCost() {
    return 100;
  }
}

/*
Example run (histogram units in milliseconds):

Simulating TimeSpreadExpirationSim {
	size = 10
	expiration = TimeExpiration(2 SECONDS)
	backgroundExpirationEnabled = false
	preciseLeakDetectionEnabled = true
	metricsRecorder = null
	threadFactory = stormpot.StormpotThreadFactory@961e946
} for BlazePool
Latency results sum:
       Value     Percentile TotalCount 1/(1-Percentile)

       0.007 0.000000000000          1           1.00
       0.046 0.500000000000       2027           2.00
       0.061 0.750000000000       3055           4.00
       0.075 0.875000000000       3555           8.00
       0.102 0.937500000000       3807          16.00
       0.127 0.968750000000       3930          32.00
       0.155 0.984375000000       3991          64.00
       0.188 0.992187500000       4022         128.00
       0.218 0.996093750000       4038         256.00
       0.258 0.998046875000       4046         512.00
     103.807 0.999023437500       4050        1024.00
     103.871 0.999511718750       4052        2048.00
     104.063 0.999755859375       4053        4096.00
     104.063 1.000000000000       4053
#[Mean    =        0.231, StdDeviation   =        4.306]
#[Max     =      104.063, Total count    =         4053]
#[Buckets =           20, SubBuckets     =         2048]

Simulating TimeSpreadExpirationSim {
	size = 10
	expiration = TimeSpreadExpiration(1 to 2 SECONDS)
	backgroundExpirationEnabled = false
	preciseLeakDetectionEnabled = true
	metricsRecorder = null
	threadFactory = stormpot.StormpotThreadFactory@961e946
} for BlazePool
Latency results sum:
       Value     Percentile TotalCount 1/(1-Percentile)

       0.003 0.000000000000          3           1.00
       0.026 0.500000000000       2064           2.00
       0.035 0.750000000000       3169           4.00
       0.045 0.875000000000       3565           8.00
       0.067 0.937500000000       3809          16.00
       0.086 0.968750000000       3927          32.00
       0.104 0.984375000000       3994          64.00
       0.126 0.992187500000       4023         128.00
       0.140 0.996093750000       4038         256.00
       0.180 0.998046875000       4046         512.00
       0.187 0.999023437500       4050        1024.00
       0.227 0.999511718750       4052        2048.00
       0.244 0.999755859375       4053        4096.00
       0.244 1.000000000000       4053
#[Mean    =        0.031, StdDeviation   =        0.021]
#[Max     =        0.244, Total count    =         4053]
#[Buckets =           20, SubBuckets     =         2048]

Done, 244.411 seconds.
 */
