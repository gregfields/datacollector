/**
 * Copyright 2015 StreamSets Inc.
 *
 * Licensed under the Apache Software Foundation (ASF) under one
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
package com.streamsets.datacollector.runner.preview;

import com.codahale.metrics.MetricRegistry;
import com.codahale.metrics.Timer;
import com.streamsets.datacollector.config.StageType;
import com.streamsets.datacollector.main.RuntimeInfo;
import com.streamsets.datacollector.metrics.MetricsConfigurator;
import com.streamsets.datacollector.restapi.bean.MetricRegistryJson;
import com.streamsets.datacollector.runner.BatchListener;
import com.streamsets.datacollector.runner.FullPipeBatch;
import com.streamsets.datacollector.runner.MultiplexerPipe;
import com.streamsets.datacollector.runner.Observer;
import com.streamsets.datacollector.runner.ObserverPipe;
import com.streamsets.datacollector.runner.Pipe;
import com.streamsets.datacollector.runner.PipeBatch;
import com.streamsets.datacollector.runner.PipeContext;
import com.streamsets.datacollector.runner.PipelineRunner;
import com.streamsets.datacollector.runner.PipelineRuntimeException;
import com.streamsets.datacollector.runner.SourceOffsetTracker;
import com.streamsets.datacollector.runner.StageOutput;
import com.streamsets.datacollector.runner.StagePipe;
import com.streamsets.datacollector.runner.production.BadRecordsHandler;
import com.streamsets.datacollector.runner.production.StatsAggregationHandler;
import com.streamsets.pipeline.api.StageException;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

public class PreviewPipelineRunner implements PipelineRunner {
  private final RuntimeInfo runtimeInfo;
  private final SourceOffsetTracker offsetTracker;
  private final int batchSize;
  private final int batches;
  private final boolean skipTargets;
  private final MetricRegistry metrics;
  private final List<List<StageOutput>> batchesOutput;
  private final String name;
  private final String rev;
  private String sourceOffset;
  private String newSourceOffset;
  private final Timer processingTimer;

  public PreviewPipelineRunner(String name, String rev, RuntimeInfo runtimeInfo, SourceOffsetTracker offsetTracker,
                               int batchSize, int batches, boolean skipTargets) {
    this.name = name;
    this.rev = rev;
    this.runtimeInfo = runtimeInfo;
    this.offsetTracker = offsetTracker;
    this.batchSize = batchSize;
    this.batches = batches;
    this.skipTargets = skipTargets;
    this.metrics = new MetricRegistry();
    processingTimer = MetricsConfigurator.createTimer(metrics, "pipeline.batchProcessing", name, rev);
    batchesOutput = new ArrayList<>();
  }

  @Override
  public MetricRegistryJson getMetricRegistryJson() {
    return null;
  }

  @Override
  public void errorNotification(Pipe originPipe, List<List<Pipe>> pipes, Throwable throwable) {
  }

  @Override
  public RuntimeInfo getRuntimeInfo() {
    return runtimeInfo;
  }

  @Override
  public boolean isPreview() {
    return true;
  }

  @Override
  public MetricRegistry getMetrics() {
    return metrics;
  }

  @Override
  @SuppressWarnings("unchecked")
  public void run(
    Pipe originPipe,
    List<List<Pipe>> pipes,
    BadRecordsHandler badRecordsHandler,
    StatsAggregationHandler statsAggregationHandler
  ) throws StageException, PipelineRuntimeException {
    run(originPipe, pipes, badRecordsHandler, Collections.EMPTY_LIST, statsAggregationHandler);
  }

  @Override
  public void run(
    Pipe originPipe,
    List<List<Pipe>> pipes,
    BadRecordsHandler badRecordsHandler,
    List<StageOutput> stageOutputsToOverride,
    StatsAggregationHandler statsAggregationHandler
  ) throws StageException, PipelineRuntimeException {
    Map<String, StageOutput> stagesToSkip = new HashMap<>();
    for (StageOutput stageOutput : stageOutputsToOverride) {
      stagesToSkip.put(stageOutput.getInstanceName(), stageOutput);
    }
    for (int i = 0; i < batches; i++) {
      PipeBatch pipeBatch = new FullPipeBatch(offsetTracker, batchSize, true);
      long start = System.currentTimeMillis();
      sourceOffset = pipeBatch.getPreviousOffset();

      // Process origin data
      StageOutput originOutput = stagesToSkip.get(originPipe.getStage().getInfo().getInstanceName());
      if(originOutput == null) {
        originPipe.process(pipeBatch);
      } else {
        pipeBatch.overrideStageOutput((StagePipe) originPipe, originOutput);
      }

      // Currently only supports single-threaded pipelines
      for (Pipe pipe : pipes.get(0)) {
        StageOutput stageOutput = stagesToSkip.get(pipe.getStage().getInfo().getInstanceName());
        if (stageOutput == null || (pipe instanceof ObserverPipe) || (pipe instanceof MultiplexerPipe) ) {
          if (!skipTargets || !pipe.getStage().getDefinition().getType().isOneOf(StageType.TARGET, StageType.EXECUTOR)) {
            pipe.process(pipeBatch);
          } else {
            pipeBatch.skipStage(pipe);
          }
        } else {
          if (pipe instanceof StagePipe) {
            pipeBatch.overrideStageOutput((StagePipe) pipe, stageOutput);
          }
        }
      }
      offsetTracker.commitOffset();
      //TODO badRecordsHandler HANDLE ERRORS
      processingTimer.update(System.currentTimeMillis() - start, TimeUnit.MILLISECONDS);
      newSourceOffset = offsetTracker.getOffset();
      batchesOutput.add(pipeBatch.getSnapshotsOfAllStagesOutput());
    }
  }

  @Override
  public void destroy(
    Pipe originPipe,
    List<List<Pipe>> pipes,
    BadRecordsHandler badRecordsHandler,
    StatsAggregationHandler statsAggregationHandler
  ) throws StageException, PipelineRuntimeException {
    // We're not doing any special event propagation during preview destroy phase

    // Destroy origin on it's own
    PipeBatch pipeBatch = new FullPipeBatch(offsetTracker, batchSize, true);
    originPipe.destroy(pipeBatch);

    // And destroy each pipeline instance separately
    for(List<Pipe> pipeRunner: pipes) {
      pipeBatch = new FullPipeBatch(offsetTracker, batchSize, true);
      pipeBatch.skipStage(originPipe);
      for(Pipe pipe : pipeRunner) {
        pipe.destroy(pipeBatch);
      }
    }
  }

  @Override
  public List<List<StageOutput>> getBatchesOutput() {
    return batchesOutput;
  }


  @Override
  public String getSourceOffset() {
    return sourceOffset;
  }

  @Override
  public String getNewSourceOffset() {
    return newSourceOffset;
  }

  @Override
  public void setObserver(Observer observer) {

  }

  @Override
  public void registerListener(BatchListener batchListener) {
    // TODO Auto-generated method stub

  }

  public void setPipeContext(PipeContext pipeContext) {

  }
}
