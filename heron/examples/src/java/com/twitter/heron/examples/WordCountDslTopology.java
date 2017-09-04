//  Copyright 2017 Twitter. All rights reserved.
//
//  Licensed under the Apache License, Version 2.0 (the "License");
//  you may not use this file except in compliance with the License.
//  You may obtain a copy of the License at
//
//  http://www.apache.org/licenses/LICENSE-2.0
//
//  Unless required by applicable law or agreed to in writing, software
//  distributed under the License is distributed on an "AS IS" BASIS,
//  WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
//  See the License for the specific language governing permissions and
//  limitations under the License.

package com.twitter.heron.examples;

import java.util.Arrays;

import com.twitter.heron.api.Config;
import com.twitter.heron.dsl.Builder;
import com.twitter.heron.dsl.Context;
import com.twitter.heron.dsl.KeyValue;
import com.twitter.heron.dsl.Streamlet;
import com.twitter.heron.dsl.WindowConfig;

/**
 * This is a topology that does simple word counts.
 * <p>
 * In this topology,
 * 1. the spout task generate a set of random words during initial "open" method.
 * (~128k words, 20 chars per word)
 * 2. During every "nextTuple" call, each spout simply picks a word at random and emits it
 * 3. Spouts use a fields grouping for their output, and each spout could send tuples to
 * every other bolt in the topology
 * 4. Bolts maintain an in-memory map, which is keyed by the word emitted by the spouts,
 * and updates the count when it receives a tuple.
 */
public final class WordCountDslTopology {
  private WordCountDslTopology() {
  }

  /**
   * Main method
   */
  public static void main(String[] args) {
    if (args.length < 1) {
      throw new RuntimeException("Specify topology name");
    }

    int parallelism = 1;
    if (args.length > 1) {
      parallelism = Integer.parseInt(args[1]);
    }
    WindowConfig windowConfig = WindowConfig.createCountWindow(10);
    Builder builder = Builder.CreateBuilder();
    Streamlet<String> source = Streamlet.createStreamlet(() -> "Mary had a little lamb");
    builder.addSource(source);
    source.flatMap((word) -> Arrays.asList(word.split("\\s+")))
          .mapToKV((word) -> new KeyValue<String, Integer>(word, 1))
          .reduceByKeyAndWindow(windowConfig, (x, y) -> x + y);
    Config conf = new Config();
    conf.setNumStmgrs(parallelism);
    Context.run("WordCount", conf, builder);
  }
}
