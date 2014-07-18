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

public interface LatencyRecorder {

  void recordAllocationLatencySampleMillis(long milliseconds);

  void recordAllocationFailureLatencySampleMillis(long milliseconds);

  void recordDeallocationLatencySampleMillis(long milliseconds);

  void recordReallocationLatencySampleMillis(long milliseconds);

  void recordReallocationFailureLatencySampleMillis(long milliseconds);

  void recordObjectLifetimeSampleMillis(long milliseconds);

  public double getAllocationLatencyPercentile(double percentile);

  public double getAllocationFailureLatencyPercentile(double percentile);

  public double getDeallocationLatencyPercentile(double percentile);

  public double getReallocationLatencyPercentile(double percentile);

  public double getReallocationFailurePercentile(double percentile);

  public double getObjectLifetimePercentile(double percentile);
}
