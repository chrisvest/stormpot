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
 * This simulation explores the latencies experienced when the pool is exposed
 * to a predictable, constant throughput, with or without background
 * expiration.
 */
@Sim.Simulation(pools = {BlazePool.class}, measurementTime = 60)
public class ConstantTrafficBackgroundExpirationSim extends Sim {

  private static final Timeout timeout = new Timeout(1, TimeUnit.SECONDS);

  @Conf(Param.expiration)
  public TimeSpreadExpiration<Poolable> expiration =
      new TimeSpreadExpiration<>(200, 1000, TimeUnit.MILLISECONDS);

  @Conf(Param.backgroundExpirationEnabled)
  public boolean[] backgroundExpiration = {true, false};

  @Agent
  public void claimRelease(Pool<Poolable> pool) throws InterruptedException {
    pool.claim(timeout).release();
  }

  @AllocationCost
  public long allocationCost() {
    return 10;
  }

  @AgentPause
  public long sleep() {
    return 100;
  }
}
/*
Example run (histogram units in milliseconds):

Simulating ConstantTrafficBackgroundExpirationSim {
	size = 10
	expiration = TimeSpreadExpiration(200 to 1000 MILLISECONDS)
	backgroundExpirationEnabled = true
	preciseLeakDetectionEnabled = true
	metricsRecorder = null
	threadFactory = stormpot.StormpotThreadFactory@2f17aadf
} for BlazePool
Latency results for Agent[]:
       Value     Percentile TotalCount 1/(1-Percentile)

       0.003 0.000000000000          1           1.00
       0.052 0.500000000000        299           2.00
       0.063 0.750000000000        441           4.00
       0.091 0.875000000000        509           8.00
       0.110 0.937500000000        547          16.00
       0.145 0.968750000000        562          32.00
       0.164 0.984375000000        571          64.00
       0.184 0.992187500000        576         128.00
       0.189 0.996093750000        578         256.00
       0.193 0.998046875000        579         512.00
       0.198 0.999023437500        580        1024.00
       0.198 1.000000000000        580
#[Mean    =        0.057, StdDeviation   =        0.031]
#[Max     =        0.198, Total count    =          580]
#[Buckets =           20, SubBuckets     =         2048]

Simulating ConstantTrafficBackgroundExpirationSim {
	size = 10
	expiration = TimeSpreadExpiration(200 to 1000 MILLISECONDS)
	backgroundExpirationEnabled = false
	preciseLeakDetectionEnabled = true
	metricsRecorder = null
	threadFactory = stormpot.StormpotThreadFactory@2f17aadf
} for BlazePool
Latency results for Agent[]:
       Value     Percentile TotalCount 1/(1-Percentile)

       0.011 0.000000000000          1           1.00
       0.053 0.500000000000        299           2.00
       0.077 0.750000000000        437           4.00
       0.122 0.875000000000        507           8.00
       0.142 0.937500000000        542          16.00
       0.169 0.968750000000        560          32.00
       0.196 0.984375000000        569          64.00
      11.455 0.992187500000        574         128.00
      11.759 0.996093750000        576         256.00
      12.631 0.998046875000        577         512.00
      12.791 0.999023437500        578        1024.00
      12.791 1.000000000000        578
#[Mean    =        0.186, StdDeviation   =        1.193]
#[Max     =       12.791, Total count    =          578]
#[Buckets =           20, SubBuckets     =         2048]

Done, 243.554 seconds.
 */
