/*
 * This file is part of RskJ
 * Copyright (C) 2026 RSK Labs Ltd.
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Lesser General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the
 * GNU Lesser General Public License for more details.
 *
 * You should have received a copy of the GNU Lesser General Public License
 * along with this program. If not, see <http://www.gnu.org/licenses/>.
 */

package co.rsk.trie;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.PriorityQueue;
import java.util.concurrent.ForkJoinPool;
import java.util.concurrent.ForkJoinTask;
import java.util.concurrent.ForkJoinWorkerThread;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Computes the hashes of the unsaved, unhashed nodes of a trie in parallel before
 * {@link TrieStoreImpl} saves it.
 * <p>
 * A node's hash, encoding, children size, message length and value hash are pure functions of the
 * node's content, memoized in the node. This class only fills those memo fields earlier, with the
 * same values the sequential save would compute, so the subsequent sequential save writes exactly
 * the same keys and values in the same order.
 * <p>
 * Safety of the off-thread work:
 * <ul>
 *     <li>A main-thread pre-pass walks every node that will be hashed ("owned": loaded, not saved,
 *     no cached hash) and computes everything that could need the store (children size, long value
 *     hash, hashes of unhashed non-owned children). Workers then never touch the store.</li>
 *     <li>The pre-pass verifies that every owned node, and every loaded child of an owned node,
 *     is reached exactly once. Otherwise it falls back to the sequential path, so the subtrees
 *     handed to workers are disjoint.</li>
 *     <li>Every task is joined before returning, which publishes the workers' writes to the caller.</li>
 * </ul>
 */
final class TrieParallelHasher {

    private static final Logger logger = LoggerFactory.getLogger("triestore");

    static final int PARALLELISM = Math.min(4, Runtime.getRuntime().availableProcessors() - 1);

    // below this many nodes to hash the fork/join overhead is not worth it
    static final int MIN_OWNED_NODES = 256;

    // subtrees smaller than this are not split further
    private static final int MIN_SPLIT_NODES = 16;

    private TrieParallelHasher() {
    }

    private static final class PoolHolder {
        private static final AtomicInteger THREAD_COUNTER = new AtomicInteger();

        static final ForkJoinPool POOL = PARALLELISM < 1 ? null : new ForkJoinPool(
                PARALLELISM,
                pool -> {
                    ForkJoinWorkerThread thread = ForkJoinPool.defaultForkJoinWorkerThreadFactory.newThread(pool);
                    thread.setDaemon(true);
                    thread.setName("trie-hasher-" + THREAD_COUNTER.incrementAndGet());
                    return thread;
                },
                null,
                false);
    }

    /**
     * @return true if some subtrees were hashed in parallel
     */
    static boolean prehash(Trie root) {
        if (PARALLELISM < 1) {
            return false;
        }
        return prehash(root, PoolHolder.POOL, PARALLELISM);
    }

    static boolean prehash(Trie root, ForkJoinPool pool, int parallelism) {
        if (pool == null || parallelism < 1 || !isOwned(root)) {
            return false;
        }

        Map<Trie, Integer> sizes = new IdentityHashMap<>();
        Map<Trie, Boolean> seen = new IdentityHashMap<>();
        int total = visit(root, sizes, seen);
        if (total < MIN_OWNED_NODES) {
            return false;
        }

        List<Trie> frontier = selectFrontier(root, sizes, parallelism * 4);
        if (frontier.size() < 2) {
            return false;
        }

        List<ForkJoinTask<?>> tasks = new ArrayList<>(frontier.size() - 1);
        for (int i = 1; i < frontier.size(); i++) {
            Trie node = frontier.get(i);
            tasks.add(pool.submit(() -> {
                node.getHash();
            }));
        }

        RuntimeException failure = null;
        try {
            // the caller thread takes the largest subtree instead of idling
            frontier.get(0).getHash();
        } catch (RuntimeException e) {
            failure = e;
        }

        // wait for every task, even if one failed, so no worker is still running when save starts
        for (ForkJoinTask<?> task : tasks) {
            try {
                task.join();
            } catch (RuntimeException e) {
                if (failure == null) {
                    failure = e;
                }
            }
        }

        if (failure != null) {
            // memo fields are only ever set to their final values, so the sequential save simply
            // recomputes whatever is missing (and surfaces the error if it is real)
            logger.debug("parallel trie hashing failed, falling back to sequential save", failure);
        }

        return true;
    }

    private static boolean isOwned(Trie node) {
        return !node.wasSaved() && !node.hasCachedHash();
    }

    /**
     * Main-thread pre-pass over an owned node. Computes every memo value whose computation could
     * read the store, records the owned subtree sizes and checks the owned region is a tree.
     *
     * @return the number of owned nodes in this subtree, or -1 if some node is reachable twice
     */
    private static int visit(Trie node, Map<Trie, Integer> sizes, Map<Trie, Boolean> seen) {
        if (seen.put(node, Boolean.TRUE) != null) {
            return -1;
        }

        // may retrieve unloaded children from the store
        node.getChildrenSize();

        if (node.valueHashNeedsStore()) {
            node.getValueHash();
        }

        int count = 1;
        for (NodeReference ref : new NodeReference[]{node.getLeft(), node.getRight()}) {
            Trie child = ref.loadedNode();
            if (child == null) {
                // workers only use the reference's hash, which it holds
                continue;
            }

            if (isOwned(child)) {
                int childCount = visit(child, sizes, seen);
                if (childCount < 0) {
                    return -1;
                }
                count += childCount;
                continue;
            }

            // workers may write memo fields of this child (message length, encoding), so it has to be unique too
            if (seen.put(child, Boolean.TRUE) != null) {
                return -1;
            }

            if (!ref.hasCachedHash() && !child.hasCachedHash() && !child.isEmbeddable()) {
                // the parent's encoding will need this hash; compute it here since it might need the store
                ref.getHash();
            }
        }

        sizes.put(node, count);
        return count;
    }

    /**
     * Splits the owned region below the root into disjoint subtrees, largest first.
     * The nodes split off (the root among them) are left for the sequential save.
     */
    private static List<Trie> selectFrontier(Trie root, Map<Trie, Integer> sizes, int target) {
        PriorityQueue<Trie> queue = new PriorityQueue<>((a, b) -> Integer.compare(sizes.get(b), sizes.get(a)));
        queue.add(root);

        while (!queue.isEmpty() && queue.size() < target) {
            Trie largest = queue.peek();
            if (sizes.get(largest) < MIN_SPLIT_NODES) {
                break;
            }
            queue.poll();
            addOwnedChild(queue, sizes, largest.getLeft());
            addOwnedChild(queue, sizes, largest.getRight());
        }

        List<Trie> frontier = new ArrayList<>(queue.size());
        while (!queue.isEmpty()) {
            Trie node = queue.poll();
            // single nodes are cheap and may be embedded (never hashed by save): leave them to save
            if (sizes.get(node) > 1) {
                frontier.add(node);
            }
        }
        return Collections.unmodifiableList(frontier);
    }

    private static void addOwnedChild(PriorityQueue<Trie> queue, Map<Trie, Integer> sizes, NodeReference ref) {
        Trie child = ref.loadedNode();
        if (child != null && sizes.containsKey(child)) {
            queue.add(child);
        }
    }
}
