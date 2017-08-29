// Copyright 2016 Twitter. All rights reserved.
//
// Licensed under the Apache License, Version 2.0 (the "License");
// you may not use this file except in compliance with the License.
// You may obtain a copy of the License at
//
//    http://www.apache.org/licenses/LICENSE-2.0
//
// Unless required by applicable law or agreed to in writing, software
// distributed under the License is distributed on an "AS IS" BASIS,
// WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
// See the License for the specific language governing permissions and
// limitations under the License.

package com.twitter.heron.dsl;

import java.util.HashSet;
import java.util.Set;
import java.util.function.Function;
import java.util.function.Predicate;

import com.twitter.heron.api.Config;
import com.twitter.heron.api.HeronSubmitter;
import com.twitter.heron.api.exception.AlreadyAliveException;
import com.twitter.heron.api.exception.InvalidTopologyException;
import com.twitter.heron.api.topology.TopologyBuilder;
import com.twitter.heron.dsl.windowing.WindowConfig;

/**
 * A Streamlet is a (potentially unbounded) ordered collection of tuples.
 * Streamlets originate from pub/sub systems(such Pulsar/Kafka), or from
 * static data(such as csv files, HDFS files), or for that matter any other
 * source. They are also created by transforming existing Streamlets using
 * operations such as map/flatMap, etc.
 * Besides the tuples, a Streamlet has the following properties associated with it
 * a) name. User assigned or system generated name to refer the streamlet
 * b) nPartitions. Number of partitions that the streamlet is composed of. Thus the
 *    ordering of the tuples in a Streamlet is wrt the tuples within a partition.
 *    This allows the system to distribute  each partition to different nodes across the cluster.
 * A bunch of transformations can be done on Streamlets(like map/flatMap, etc.). Each
 * of these transformations operate on every tuple of the Streamlet and produce a new
 * Streamlet. One can think of a transformation attaching itself to the stream and processing
 * each tuple as they go by. Thus the parallelism of any operator is implicitly determined
 * by the number of partitions of the stream that it is operating on. If a particular
 * tranformation wants to operate at a different parallelism, one can repartition the
 * Streamlet before doing the transformation.
 */
public abstract class Streamlet<R> {
  protected String name;
  protected int nPartitions;

  /**
   * Sets the name of the Streamlet.
   * @param sName The name given by the user for this streamlet
   * @return Returns back the Streamlet with changed name
  */
  public Streamlet<R> setName(String sName) {
    if (sName == null) {
      throw new IllegalArgumentException("Streamlet name cannot be null");
    }
    this.name = sName;
    return this;
  }

  /**
   * Gets the name of the Streamlet.
   * @return Returns the name of the Streamlet
   */
  public String getName() {
    return name;
  }

  /**
   * Sets the number of partitions of the streamlet
   * @param numPartitions The user assigned number of partitions
   * @return Returns back the Streamlet with changed number of partitions
   */
  public Streamlet<R> setNumPartitions(int numPartitions) {
    if (numPartitions < 1) {
      throw new IllegalArgumentException("Streamlet's partitions cannot be < 1");
    }
    this.nPartitions = numPartitions;
    return this;
  }

  /**
   * Gerts the number of partitions of this Streamlet.
   * @return the number of partitions of this Streamlet
   */
  public int getNumPartitions() {
    return nPartitions;
  }

  /**
   * Return a new Streamlet by applying mapFn to each element of this Streamlet
   * @param mapFn The Map Function that should be applied to each element
  */

  <T> Streamlet<T> map(Function<R, T> mapFn) {
    return new MapStreamlet<R, T>(this, mapFn);
  }

  /**
   * Return a new KVStreamlet by applying mapFn to each element of this Streamlet.
   * This differs from the above map transformation in that it returns a KVStreamlet
   * instead of a plain Streamlet.
   * @param mapFn The Map function that should be applied to each element
  */
  <K, V> KVStreamlet<K, V> mapToKV(Function<R, KeyValue<K, V>> mapFn) {
    return new KVMapStreamlet<R, K, V>(this, mapFn);
  }

  /**
   * Return a new Streamlet by applying flatMapFn to each element of this Streamlet and
   * flattening the result
   * @param flatMapFn The FlatMap Function that should be applied to each element
  */
  <T> Streamlet<T> flatMap(Function<R, Iterable<T>> flatMapFn) {
    return new FlatMapStreamlet<R, T>(this, flatMapFn);
  }

  /**
   * Return a new KVStreamlet by applying map_function to each element of this Streamlet
   * and flattening the result. It differs from the above flatMap in that it returns a
   * KVStreamlet instead of a plain Streamlet
   * @param flatMapFn The FlatMap Function that should be applied to each element
  */
  <K, V> KVStreamlet<K, V> flatMapToKV(Function<R, Iterable<KeyValue<K, V>>> flatMapFn) {
    return new KVFlatMapStreamlet<R, K, V>(this, flatMapFn);
  }

  /**
   * Return a new Streamlet by applying the filterFn on each element of this streamlet
   * and including only those elements that satisfy the filterFn
   * @param filterFn The filter Function that should be applied to each element
  */
  Streamlet<R> filter(Predicate<R> filterFn) {
    return new FilterStreamlet<R>(this, filterFn);
  }

  /**
   * Same as filter(filterFn).setNumPartitions(nPartitions) where filterFn is identity
  */
  Streamlet<R> repartition(int numPartitions) {
    return this.map(Function.identity()).setNumPartitions(numPartitions);
  }

  /**
   * Returns a new Streamlet by accumulating tuples of this streamlet over a WindowConfig
   * windowConfig and applying reduceFn on those tuples
   * @param windowConfig This is a specification of what kind of windowing strategy you like
   * to have. Typical windowing strategies are sliding windows and tumbling windows
   * @param reduceFn The reduceFn to apply over the tuples accumulated on a window
   */
  Streamlet<I> reduceByWindow(WindowConfig windowConfig, BinaryOperator<I> reduceFn) {
    return new ReduceByWindowStreamlet<I>(this, windowConfig, reduceFn);
  }

  /**
   * Returns a new Streamlet thats the union of this and the ‘other’ streamlet. Essentially
   * the new streamlet will contain tuples belonging to both Streamlets
  */
  Streamlet<I> union(Streamlet<I> other) {
    return new UnionStreamlet<I>(this, other);
  }


  protected Streamlet() {
    this.nPartitions = -1;
  }

  abstract TopologyBuilder build(TopologyBuilder bldr, Set<String> stageNames);

  public void run(String topologyName, Config config) {
    Set<String> stageNames = new HashSet<>();
    TopologyBuilder bldr = new TopologyBuilder();
    bldr = build(bldr, stageNames);
    try {
      HeronSubmitter.submitTopology(topologyName, config, bldr.createTopology());
    } catch (AlreadyAliveException e) {
      e.printStackTrace();
    } catch (InvalidTopologyException e) {
      e.printStackTrace();
    }
  }
}
