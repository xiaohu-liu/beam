/*
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.apache.beam.runners.jstorm;

import static com.alibaba.jstorm.metric.AsmWindow.M10_WINDOW;
import static com.google.common.base.Preconditions.checkNotNull;

import backtype.storm.Config;
import backtype.storm.LocalCluster;
import com.alibaba.jstorm.common.metric.AsmGauge;
import com.alibaba.jstorm.metric.AsmMetricRegistry;
import com.alibaba.jstorm.metric.JStormMetrics;
import com.alibaba.jstorm.utils.JStormUtils;
import java.io.IOException;
import java.util.Map;
import org.apache.beam.runners.jstorm.translation.CommonInstance;
import org.apache.beam.runners.jstorm.translation.JStormMetricResults;
import org.apache.beam.sdk.PipelineResult;
import org.apache.beam.sdk.metrics.MetricResults;
import org.apache.beam.sdk.transforms.windowing.BoundedWindow;
import org.apache.beam.sdk.util.BackOff;
import org.apache.beam.sdk.util.BackOffUtils;
import org.apache.beam.sdk.util.FluentBackoff;
import org.apache.beam.sdk.util.Sleeper;
import org.joda.time.Duration;

/**
 * A {@link PipelineResult} of executing {@link org.apache.beam.sdk.Pipeline Pipelines} using
 * {@link JStormRunner}.
 */
public abstract class JStormRunnerResult implements PipelineResult {

  public static JStormRunnerResult local(
      String topologyName,
      Config config,
      LocalCluster localCluster,
      long localModeExecuteTimeSecs) {
    return new LocalJStormPipelineResult(
        topologyName, config, localCluster, localModeExecuteTimeSecs);
  }

  private final String topologyName;
  private final Config config;

  JStormRunnerResult(String topologyName, Config config) {
    this.config = checkNotNull(config, "config");
    this.topologyName = checkNotNull(topologyName, "topologyName");
  }

  public State getState() {
    return null;
  }

  public Config getConfig() {
    return config;
  }

  public String getTopologyName() {
    return topologyName;
  }

  private static class LocalJStormPipelineResult extends JStormRunnerResult {

    private final LocalCluster localCluster;
    private final long localModeExecuteTimeSecs;
    private boolean cancelled;

    LocalJStormPipelineResult(
        String topologyName,
        Config config,
        LocalCluster localCluster,
        long localModeExecuteTimeSecs) {
      super(topologyName, config);
      this.localCluster = checkNotNull(localCluster, "localCluster");
      this.localModeExecuteTimeSecs = localModeExecuteTimeSecs;
      this.cancelled = false;
    }

    @Override
    public State cancel() throws IOException {
      localCluster.killTopology(getTopologyName());
      localCluster.shutdown();
      JStormUtils.sleepMs(1000);
      cancelled = true;
      return State.CANCELLED;
    }

    @Override
    public State waitUntilFinish(Duration duration) {
      if (cancelled) {
        return State.CANCELLED;
      }
      Sleeper sleeper = Sleeper.DEFAULT;
      BackOff backOff = FluentBackoff.DEFAULT.withMaxCumulativeBackoff(duration).backoff();
      try {
        do {
          if (globalWatermark() >= BoundedWindow.TIMESTAMP_MAX_VALUE.getMillis()) {
            return State.DONE;
          }
        } while (BackOffUtils.next(sleeper, backOff));
      } catch (InterruptedException e) {
        Thread.currentThread().interrupt();
        // Ignore InterruptedException
      } catch (IOException e) {
        throw new RuntimeException(e);
      }
      return State.RUNNING;
    }

    @Override
    public State waitUntilFinish() {
      return waitUntilFinish(Duration.standardSeconds(localModeExecuteTimeSecs));
    }

    @Override
    public MetricResults metrics() {
      return new JStormMetricResults();
    }

    private long globalWatermark() {
      AsmMetricRegistry metricRegistry = JStormMetrics.getTaskMetrics();
      boolean foundWatermark = false;
      double min = BoundedWindow.TIMESTAMP_MAX_VALUE.getMillis();
      for (Map.Entry<String, AsmGauge> entry : metricRegistry.getGauges().entrySet()) {
        if (entry.getKey().endsWith(CommonInstance.BEAM_OUTPUT_WATERMARK_METRICS)) {
          foundWatermark = true;
          double outputWatermark = (double) entry.getValue().getValue(M10_WINDOW);
          if (outputWatermark < min) {
            min = outputWatermark;
          }
        }
      }
      if (foundWatermark) {
        return (long) min;
      } else {
        return BoundedWindow.TIMESTAMP_MIN_VALUE.getMillis();
      }
    }
  }
}
