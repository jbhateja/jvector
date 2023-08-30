/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.github.jbellis.jvector.graph;

import com.github.jbellis.jvector.util.Accountable;
import com.github.jbellis.jvector.util.RamUsageEstimator;
import org.cliffc.high_scale_lib.NonBlockingHashMapLong;

import java.util.PrimitiveIterator;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BiFunction;

/**
 * An {@link GraphIndex} that offers concurrent access; for typical graphs you will get significant
 * speedups in construction and searching as you add threads.
 *
 * <p>To search this graph, you should use a View obtained from {@link #getView()} to perform `seek`
 * and `nextNeighbor` operations.
 */
public final class OnHeapGraphIndex<T> implements GraphIndex<T>, Accountable {
  // the current graph entry node on the top level. -1 if not set
  private final AtomicReference<Integer> entryPoint; 

  // Unlike OnHeapGraphIndex (OHHG), we use the same data structure for Level 0 and higher node
  // lists, a ConcurrentHashMap.  While the ArrayList used for L0 in OHHG is faster for
  // single-threaded workloads, it imposes an unacceptable contention burden for concurrent
  // graph building.
  private final NonBlockingHashMapLong<ConcurrentNeighborSet> nodes;

  // Neighbours' size on upper levels (nsize) and level 0 (nsize0)
  final int nsize;
  final int nsize0;
  private final BiFunction<Integer, Integer, ConcurrentNeighborSet> neighborFactory;

  OnHeapGraphIndex(
      int M, BiFunction<Integer, Integer, ConcurrentNeighborSet> neighborFactory) {
    this.neighborFactory = neighborFactory;
    this.entryPoint =
        new AtomicReference<>(-1); // Entry node should be negative until a node is added
    this.nsize = M;
    this.nsize0 = 2 * M;

    this.nodes = new NonBlockingHashMapLong<>();
  }

  /**
   * Returns the neighbors connected to the given node.
   *
   * @param node the node whose neighbors are returned, represented as an ordinal on the level 0.
   */
  public ConcurrentNeighborSet getNeighbors(int node) {
    return nodes.get(node);
  }

  @Override
  public int size() {
    return nodes.size();
  }

  /**
   * Add node on the given level with an empty set of neighbors.
   *
   * <p>Nodes can be inserted out of order, but it requires that the nodes preceded by the node
   * inserted out of order are eventually added.
   *
   * <p>Actually populating the neighbors, and establishing bidirectional links, is the
   * responsibility of the caller.
   *
   * <p>It is also the responsibility of the caller to ensure that each node is only added once.
   *
   * @param node the node to add, represented as an ordinal on the level 0.
   */
  public void addNode(int node) {
    nodes.put(node, neighborFactory.apply(node, connectionsOnLevel(0)));
  }

  /** must be called after addNode once neighbors are linked in all levels. */
  void markComplete(int node) {
    entryPoint.accumulateAndGet(
        node,
        (oldEntry, newEntry) -> {
          if (oldEntry >= 0) {
            return oldEntry;
          } else {
            return newEntry;
          }
        });
  }

  public void updateEntryNode(int node) {
    entryPoint.set(node);
  }

  private int connectionsOnLevel(int level) {
    return level == 0 ? nsize0 : nsize;
  }

  int entry() {
    return entryPoint.get();
  }

  public T getVector(int node) {
    throw new UnsupportedOperationException("All searches done with OnHeapGraphIndex should be exact");
  }

  public int getNeighborCount(int node) {
    // TODO
    throw new UnsupportedOperationException();
  }

  @Override
  public NodesIterator getNodes() {
    // We avoid the temptation to optimize L0 by using ArrayNodesIterator.
    // This is because, while L0 will contain sequential ordinals once the graph is complete,
    // and internally Lucene only calls getNodesOnLevel at that point, this is a public
    // method so we cannot assume that that is the only time it will be called by third parties.
    var keysInts = nodes.keySet().stream().mapToInt(Long::intValue).iterator();
    return new NodesIterator(nodes.size()) {
      @Override
      public int nextInt() {
        return keysInts.nextInt();
      }

      @Override
      public boolean hasNext() {
        return keysInts.hasNext();
      }
    };
  }

  @Override
  public long ramBytesUsed() {
    // the main graph structure
    long total = concurrentHashMapRamUsed(size());
    long chmSize = concurrentHashMapRamUsed(size());
    long neighborSize = neighborsRamUsed(connectionsOnLevel(0)) * size();

    total += chmSize + neighborSize;

    return total;
  }

  public long ramBytesUsedOneNode(int nodeLevel) {
    int entryCount = (int) (nodeLevel / CHM_LOAD_FACTOR);
    var graphBytesUsed =
        chmEntriesRamUsed(entryCount)
            + neighborsRamUsed(connectionsOnLevel(0))
            + nodeLevel * neighborsRamUsed(connectionsOnLevel(1));
    var clockBytesUsed = Integer.BYTES;
    return graphBytesUsed + clockBytesUsed;
  }

  private static long neighborsRamUsed(int count) {
    long REF_BYTES = RamUsageEstimator.NUM_BYTES_OBJECT_REF;
    long AH_BYTES = RamUsageEstimator.NUM_BYTES_ARRAY_HEADER;
    long neighborSetBytes =
        REF_BYTES // atomicreference
            + Integer.BYTES
            + Integer.BYTES
            + REF_BYTES // NeighborArray
            + AH_BYTES * 2 // NeighborArray internals
            + REF_BYTES * 2
            + Integer.BYTES
            + 1;
    return neighborSetBytes + (long) count * (Integer.BYTES + Float.BYTES);
  }

  private static final float CHM_LOAD_FACTOR = 0.75f; // this is hardcoded inside ConcurrentHashMap

  /**
   * caller's responsibility to divide number of entries by load factor to get internal node count
   */
  private static long chmEntriesRamUsed(int internalEntryCount) {
    long REF_BYTES = RamUsageEstimator.NUM_BYTES_OBJECT_REF;
    long chmNodeBytes =
        REF_BYTES // node itself in Node[]
            + 3L * REF_BYTES
            + Integer.BYTES; // node internals

    return internalEntryCount * chmNodeBytes;
  }

  private static long concurrentHashMapRamUsed(int externalSize) {
    long REF_BYTES = RamUsageEstimator.NUM_BYTES_OBJECT_REF;
    long AH_BYTES = RamUsageEstimator.NUM_BYTES_ARRAY_HEADER;
    long CORES = Runtime.getRuntime().availableProcessors();

    // CHM has a striped counter Cell implementation, we expect at most one per core
    long chmCounters = AH_BYTES + CORES * (REF_BYTES + Long.BYTES);

    int nodeCount = (int) (externalSize / CHM_LOAD_FACTOR);

    long chmSize =
        chmEntriesRamUsed(nodeCount) // nodes
            + nodeCount * REF_BYTES
            + AH_BYTES // nodes array
            + Long.BYTES
            + 3 * Integer.BYTES
            + 3 * REF_BYTES // extra internal fields
            + chmCounters
            + REF_BYTES; // the Map reference itself
    return chmSize;
  }

  @Override
  public String toString() {
    return "OnHeapGraphIndex(size=%d, entryPoint=%d)".formatted(size(), entryPoint.get());
  }

  /**
   * Returns a view of the graph that is safe to use concurrently with updates performed on the
   * underlying graph.
   *
   * <p>Multiple Views may be searched concurrently.
   */
  public GraphIndex.View getView() {
    return new ConcurrentGraphIndexView();
  }

  void validateEntryNode() {
    if (size() == 0) {
      return;
    }
    var en = entryPoint.get();
    if (!(en >= 0 && nodes.containsKey(en))) {
      throw new IllegalStateException("Entry node was incompletely added! " + en);
    }
  }

  private class ConcurrentGraphIndexView implements GraphIndex.View {
    public Object getVector(int node) {
      throw new UnsupportedOperationException("All searches done with OnHeapGraphIndex should be exact");
    }

    public NodesIterator getNeighborsIterator(int node) {
      return getNeighbors(node).nodeIterator();
    }

    @Override
    public int size() {
      return OnHeapGraphIndex.this.size();
    }

    @Override
    public int entryNode() {
      return OnHeapGraphIndex.this.entryPoint.get();
    }

    @Override
    public String toString() {
      return "OnHeapGraphIndexView(size=" + size() + ", entryPoint=" + entryPoint.get();
    }
  }
}