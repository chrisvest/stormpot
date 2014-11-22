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
  public TimeSpreadExpiration expiration =
      new TimeSpreadExpiration(200, 1000, TimeUnit.MILLISECONDS);

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
