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

public class FixedMeanMetricsRecorder implements MetricsRecorder {
  private final double fixedAllocationLatency;
  private final double fixedObjectLifetime;
  private final double fixedAllocationFailureLatency;
  private final double fixedReallocationLatencyPercentile;
  private final double fixedReallocationFailureLatencyPercentile;
  private final double fixedDeallocationLatencyPercentile;

  public FixedMeanMetricsRecorder(
      double fixedObjectLifetime,
      double fixedAllocationLatency,
      double fixedAllocationFailureLatency,
      double fixedReallocationLatencyPercentile,
      double fixedReallocationFailureLatencyPercentile,
      double fixedDeallocationLatencyPercentile) {
    this.fixedObjectLifetime = fixedObjectLifetime;
    this.fixedAllocationLatency = fixedAllocationLatency;
    this.fixedAllocationFailureLatency = fixedAllocationFailureLatency;
    this.fixedReallocationLatencyPercentile = fixedReallocationLatencyPercentile;
    this.fixedReallocationFailureLatencyPercentile = fixedReallocationFailureLatencyPercentile;
    this.fixedDeallocationLatencyPercentile = fixedDeallocationLatencyPercentile;
  }

  @Override
  public void recordObjectLifetimeSampleMillis(long milliseconds) {
    // ignore
  }

  @Override
  public void recordAllocationLatencySampleMillis(long milliseconds) {
    // ignore
  }

  @Override
  public void recordAllocationFailureLatencySampleMillis(long milliseconds) {
    // ignore
  }

  @Override
  public void recordDeallocationLatencySampleMillis(long milliseconds) {
    // ignore
  }

  @Override
  public void recordReallocationLatencySampleMillis(long milliseconds) {
    // ignore
  }

  @Override
  public void recordReallocationFailureLatencySampleMillis(long milliseconds) {
    // ignore
  }

  @Override
  public double getObjectLifetimePercentile(double percentile) {
    if (percentile == 0.5) {
      return fixedObjectLifetime;
    }
    return NaN;
  }

  @Override
  public double getAllocationLatencyPercentile(double percentile) {
    if (percentile == 0.5) {
      return fixedAllocationLatency;
    }
    return NaN;
  }

  @Override
  public double getAllocationFailureLatencyPercentile(double percentile) {
    if (percentile == 0.5) {
      return fixedAllocationFailureLatency;
    }
    return NaN;
  }

  @Override
  public double getDeallocationLatencyPercentile(double percentile) {
    if (percentile == 0.5) {
      return fixedDeallocationLatencyPercentile;
    }
    return NaN;
  }

  @Override
  public double getReallocationLatencyPercentile(double percentile) {
    if (percentile == 0.5) {
      return fixedReallocationLatencyPercentile;
    }
    return NaN;
  }

  @Override
  public double getReallocationFailurePercentile(double percentile) {
    if (percentile == 0.5) {
      return fixedReallocationFailureLatencyPercentile;
    }
    return NaN;
  }
}
