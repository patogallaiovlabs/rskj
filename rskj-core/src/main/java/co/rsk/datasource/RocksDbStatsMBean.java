/*
 * This file is part of RskJ
 * Copyright (C) 2025 RSK Labs Ltd.
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

package co.rsk.datasource;

/**
 * JMX MBean interface for exposing RocksDB memory statistics.
 * <p>
 * These metrics are scraped by the jmx_prometheus_javaagent and published to
 * Prometheus, making them available in Grafana dashboards.
 *
 * Key metrics (sourced from RocksDB Wiki: Memory usage in RocksDB):
 * - Block Cache Usage: bytes currently occupied in the shared LRU block cache.
 * - Index/Filter Memory: estimate of heap used by SST indexes and bloom filters.
 * - Memtable Size: current size of all active in-memory write buffers.
 * - Block Cache Pinned: bytes currently pinned (in-use) by active iterators.
 *
 * @see <a href="https://github.com/facebook/rocksdb/wiki/Memory-usage-in-RocksDB">RocksDB Memory Usage</a>
 */
public interface RocksDbStatsMBean {

    /**
     * Total bytes used in the shared LRU Block Cache across all RocksDB instances.
     * This is bounded by database.rocksdb.sharedBlockCacheSize.
     *
     * @return block cache usage in bytes, or -1 if unavailable
     */
    long getBlockCacheUsageBytes();

    /**
     * Total bytes pinned in the block cache by active iterators.
     * Elevated values indicate heavy concurrent read operations.
     *
     * @return pinned block cache usage in bytes, or -1 if unavailable
     */
    long getBlockCachePinnedUsageBytes();

    /**
     * Estimated memory used by SST file index and bloom filter blocks.
     * This is the memory outside block cache consumed by "table readers".
     * With cacheIndexAndFilterBlocks=true this should be near-zero, but
     * a non-zero value indicates some indexes are resident outside the cache.
     *
     * @return estimated table readers memory in bytes, or -1 if unavailable
     */
    long getEstimateTableReadersMem();

    /**
     * Total size of all active memtables (in-memory write buffers) across all instances.
     * Each DB instance has a write buffer. When the memtable fills up, it gets flushed
     * to an SST file on disk and memory is released.
     *
     * @return total memtable size in bytes, or -1 if unavailable
     */
    long getCurSizeAllMemTables();

    /**
     * Approximate total bytes of data in the memtable (unflushed writes).
     *
     * @return memtable unflushed bytes, or -1 if unavailable
     */
    long getCurSizeActiveMemTable();

    /**
     * Estimated number of bytes to be compacted. 
     * Useful for tracking write backlog.
     *
     * @return estimated pending compaction bytes, or -1 if unavailable
     */
    long getEstimatePendingCompactionBytes();

    /**
     * Approximate size of live data in bytes (vs. total disk space).
     *
     * @return estimated live data size in bytes, or -1 if unavailable
     */
    long getEstimateLiveDataSize();

    /**
     * Approximate number of keys in the database.
     *
     * @return estimated number of keys, or -1 if unavailable
     */
    long getEstimateNumKeys();

    /**
     * Total number of SST files across all DB instances and levels.
     * @deprecated Use getTotalSstFilesSizeBytes() for memory analysis.
     *
     * @return number of SST files, or -1 if unavailable
     */
    @Deprecated
    long getNumSstFiles();

    /**
     * Total size of all SST files in bytes.
     * Corrected name for the property previously exposed as getNumSstFiles.
     *
     * @return total SST files size in bytes, or -1 if unavailable
     */
    long getTotalSstFilesSizeBytes();

    /**
     * Number of RocksDB instances (databases) currently tracked.
     *
     * @return number of registered DB instances
     */
    int getDbInstanceCount();
}
