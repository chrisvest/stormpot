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
  public TimeExpiration expiration = new TimeExpiration(6, TimeUnit.SECONDS);

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
