/*
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.apache.flink.table.plan.nodes.resource.parallelism;

import org.apache.flink.table.api.TableEnvironment;
import org.apache.flink.table.plan.nodes.calcite.Sink;
import org.apache.flink.table.plan.nodes.exec.ExecNode;
import org.apache.flink.table.plan.nodes.physical.batch.BatchExecSink;
import org.apache.flink.table.plan.nodes.physical.stream.StreamExecSink;
import org.apache.flink.table.plan.nodes.process.DAGProcessContext;
import org.apache.flink.table.plan.nodes.process.DAGProcessor;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Processor for calculating parallelism for {@link ExecNode} dag.
 */
public class ParallelismProcessor implements DAGProcessor {

	@Override
	public List<ExecNode<?, ?>> process(List<ExecNode<?, ?>> sinkNodes, DAGProcessContext context) {
		TableEnvironment tEnv = context.getTableEnvironment();
		List<ExecNode<?, ?>> rootNodes = filterSinkNodes(sinkNodes);
		// find exec nodes whose parallelism cannot be changed.
		Map<ExecNode<?, ?>, Integer> nodeToFinalParallelismMap = FinalParallelismSetter.calculate(tEnv.streamEnv(), rootNodes);
		// generate shuffleStages that bind adjacent exec nodes together whose parallelism can be the same.
		Map<ExecNode<?, ?>, ShuffleStage> nodeShuffleStageMap = ShuffleStageGenerator.generate(rootNodes, nodeToFinalParallelismMap);
		// calculate parallelism of shuffleStages.
		ShuffleStageParallelismCalculator.calculate(tEnv.getConfig().getConf(), tEnv.streamEnv().getParallelism(), nodeShuffleStageMap.values());
		for (ExecNode<?, ?> node : nodeShuffleStageMap.keySet()) {
			node.getResource().setParallelism(nodeShuffleStageMap.get(node).getParallelism());
		}
		return sinkNodes;
	}

	/**
	 * Filter sink nodes because parallelism of sink nodes is calculated after translateToPlan, as
	 * transformations generated by {@link BatchExecSink} or {@link StreamExecSink} have too many
	 * uncertainty factors. Filtering here can let later process easier.
	 */
	private List<ExecNode<?, ?>> filterSinkNodes(List<ExecNode<?, ?>> sinkNodes) {
		List<ExecNode<?, ?>> rootNodes = new ArrayList<>();
		sinkNodes.forEach(s -> {
			if (s instanceof Sink) {
				rootNodes.add(s.getInputNodes().get(0));
			} else {
				rootNodes.add(s);
			}
		});
		return rootNodes;
	}
}
