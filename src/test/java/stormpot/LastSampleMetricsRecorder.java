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

import static java.lang.Double.NaN;

public class LastSampleMetricsRecorder implements MetricsRecorder {
  private double allocationLatency = NaN;
  private double allocationFailureLatency = NaN;
  private double deallocationLatency = NaN;
  private double reallocationLatency = NaN;
  private double reallocationFailureLatency = NaN;
  private double objectLifetime = NaN;

  @Override
  public synchronized void recordAllocationLatencySampleMillis(long milliseconds) {
    allocationLatency = milliseconds;
  }

  @Override
  public void recordAllocationFailureLatencySampleMillis(long milliseconds) {
    allocationFailureLatency = milliseconds;
  }

  @Override
  public void recordDeallocationLatencySampleMillis(long milliseconds) {
    deallocationLatency = milliseconds;
  }

  @Override
  public void recordReallocationLatencySampleMillis(long milliseconds) {
    reallocationLatency = milliseconds;
  }

  @Override
  public void recordReallocationFailureLatencySampleMillis(long milliseconds) {
    reallocationFailureLatency = milliseconds;
  }

  @Override
  public synchronized void recordObjectLifetimeSampleMillis(long milliseconds) {
    objectLifetime = milliseconds;
  }

  @Override
  public synchronized double getAllocationLatencyPercentile(double percentile) {
    return allocationLatency;
  }

  @Override
  public double getAllocationFailureLatencyPercentile(double percentile) {
    return allocationFailureLatency;
  }

  @Override
  public double getDeallocationLatencyPercentile(double percentile) {
    return deallocationLatency;
  }

  @Override
  public double getReallocationLatencyPercentile(double percentile) {
    return reallocationLatency;
  }

  @Override
  public double getReallocationFailurePercentile(double percentile) {
    return reallocationFailureLatency;
  }

  @Override
  public synchronized double getObjectLifetimePercentile(double percentile) {
    return objectLifetime;
  }
}
