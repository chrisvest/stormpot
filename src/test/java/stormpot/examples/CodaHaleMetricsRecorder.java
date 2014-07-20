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
package stormpot.examples;

import com.codahale.metrics.Histogram;
import com.codahale.metrics.MetricRegistry;
import stormpot.MetricsRecorder;

public class CodaHaleMetricsRecorder implements MetricsRecorder {
  private final Histogram allocationLatency;
  private final Histogram allocationFailureLatency;
  private final Histogram deallocationLatency;
  private final Histogram reallocationLatency;
  private final Histogram reallocationFailureLatency;
  private final Histogram objectLifetime;

  public CodaHaleMetricsRecorder(String baseName, MetricRegistry registry) {
    allocationLatency = registry.histogram(baseName + ".allocationLatency");
    allocationFailureLatency = registry.histogram(baseName + ".allocationFailureLatency");
    deallocationLatency = registry.histogram(baseName + ".deallocationLatency");
    reallocationLatency = registry.histogram(baseName + ".reallocationLatency");
    reallocationFailureLatency = registry.histogram(baseName + ".reallocationFailureLatency");
    objectLifetime = registry.histogram(baseName + ".objectLifetime");
  }

  @Override
  public void recordAllocationLatencySampleMillis(long milliseconds) {
    allocationLatency.update(milliseconds);
  }

  @Override
  public void recordAllocationFailureLatencySampleMillis(long milliseconds) {
    allocationFailureLatency.update(milliseconds);
  }

  @Override
  public void recordDeallocationLatencySampleMillis(long milliseconds) {
    deallocationLatency.update(milliseconds);
  }

  @Override
  public void recordReallocationLatencySampleMillis(long milliseconds) {
    reallocationLatency.update(milliseconds);
  }

  @Override
  public void recordReallocationFailureLatencySampleMillis(long milliseconds) {
    reallocationFailureLatency.update(milliseconds);
  }

  @Override
  public void recordObjectLifetimeSampleMillis(long milliseconds) {
    objectLifetime.update(milliseconds);
  }

  @Override
  public double getAllocationLatencyPercentile(double percentile) {
    return allocationLatency.getSnapshot().getValue(percentile);
  }

  @Override
  public double getAllocationFailureLatencyPercentile(double percentile) {
    return allocationFailureLatency.getSnapshot().getValue(percentile);
  }

  @Override
  public double getDeallocationLatencyPercentile(double percentile) {
    return deallocationLatency.getSnapshot().getValue(percentile);
  }

  @Override
  public double getReallocationLatencyPercentile(double percentile) {
    return reallocationLatency.getSnapshot().getValue(percentile);
  }

  @Override
  public double getReallocationFailurePercentile(double percentile) {
    return reallocationFailureLatency.getSnapshot().getValue(percentile);
  }

  @Override
  public double getObjectLifetimePercentile(double percentile) {
    return objectLifetime.getSnapshot().getValue(percentile);
  }
}
