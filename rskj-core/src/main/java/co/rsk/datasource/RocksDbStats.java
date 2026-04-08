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

import org.rocksdb.RocksDB;
import org.rocksdb.RocksDBException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.management.*;
import java.lang.management.ManagementFactory;

/**
 * Collects and exposes RocksDB internal statistics as JMX metrics.
 * <p>
 * This singleton is automatically picked up by the jmx_prometheus_javaagent already
 * configured in the RSKj Docker image, making all metrics available in Prometheus/Grafana
 * under the prefix: co.rsk.datasource:type=RocksDbStats
 * <p>
 * Metrics are sourced from the documented RocksDB DB properties:
 * - rocksdb.block-cache-usage
 * - rocksdb.block-cache-pinned-usage
 * - rocksdb.estimate-table-readers-mem
 * - rocksdb.cur-size-all-mem-tables
 * - rocksdb.cur-size-active-mem-table
 * - rocksdb.num-running-compactions
 * - rocksdb.num-files-at-level0 (all levels summed)
 *
 * @see <a href="https://github.com/facebook/rocksdb/wiki/Memory-usage-in-RocksDB">RocksDB Memory Statistics</a>
 * @see <a href="https://github.com/facebook/rocksdb/wiki/Statistics">RocksDB Properties</a>
 */
/**
 * Collects and exposes RocksDB internal statistics as JMX metrics for a specific database instance.
 * <p>
 * Each RocksDbDataSource registers its own instance of this class, allowing Grafana to
 * distinguish between 'state', 'blocks', and other databases using the 'name' label.
 */
public class RocksDbStats implements RocksDbStatsMBean {

    private static final Logger logger = LoggerFactory.getLogger(RocksDbStats.class);

    // Default JMX domain for RSK datasource metrics
    public static final String JMX_DOMAIN = "co.rsk.datasource";

    private final RocksDB db;
    private final String dbName;
    private final ObjectName objectName;

    public RocksDbStats(RocksDB db, String dbName) {
        this.db = db;
        this.dbName = dbName;
        this.objectName = createObjectName(dbName);

        registerMBean();
    }

    private ObjectName createObjectName(String name) {
        try {
            return new ObjectName(String.format("%s:type=RocksDbStats,name=%s", JMX_DOMAIN, name));
        } catch (MalformedObjectNameException e) {
            throw new RuntimeException("Invalid JMX name for database: " + name, e);
        }
    }

    private void registerMBean() {
        try {
            MBeanServer mbs = ManagementFactory.getPlatformMBeanServer();
            if (mbs.isRegistered(objectName)) {
                mbs.unregisterMBean(objectName);
            }
            mbs.registerMBean(this, objectName);
            logger.info("RocksDbStats MBean registered for database: {}", dbName);
        } catch (Exception e) {
            logger.warn("Failed to register RocksDbStats MBean for {}: {}", dbName, e.getMessage());
        }
    }

    /**
     * Unregisters the MBean from the platform server. Should be called when the DB is closed.
     */
    public void unregister() {
        try {
            MBeanServer mbs = ManagementFactory.getPlatformMBeanServer();
            if (mbs.isRegistered(objectName)) {
                mbs.unregisterMBean(objectName);
                logger.debug("RocksDbStats MBean unregistered for database: {}", dbName);
            }
        } catch (Exception e) {
            logger.warn("Failed to unregister RocksDbStats MBean for {}: {}", dbName, e.getMessage());
        }
    }

    // ─── JMX property implementations ────────────────────────────────────────────

    @Override
    public long getBlockCacheUsageBytes() {
        return getPropertyLong("rocksdb.block-cache-usage");
    }

    @Override
    public long getBlockCachePinnedUsageBytes() {
        return getPropertyLong("rocksdb.block-cache-pinned-usage");
    }

    @Override
    public long getEstimateTableReadersMem() {
        return getPropertyLong("rocksdb.estimate-table-readers-mem");
    }

    @Override
    public long getCurSizeAllMemTables() {
        return getPropertyLong("rocksdb.cur-size-all-mem-tables");
    }

    @Override
    public long getCurSizeActiveMemTable() {
        return getPropertyLong("rocksdb.cur-size-active-mem-table");
    }

    @Override
    public long getEstimatePendingCompactionBytes() {
        return getPropertyLong("rocksdb.estimate-pending-compaction-bytes");
    }

    @Override
    public long getEstimateLiveDataSize() {
        return getPropertyLong("rocksdb.estimate-live-data-size");
    }

    @Override
    public long getEstimateNumKeys() {
        return getPropertyLong("rocksdb.estimate-num-keys");
    }

    @Deprecated
    @Override
    public long getNumSstFiles() {
        return getTotalSstFilesSizeBytes();
    }

    @Override
    public long getTotalSstFilesSizeBytes() {
        return getPropertyLong("rocksdb.total-sst-files-size");
    }

    @Override
    public int getDbInstanceCount() {
        return 1; // Now per-instance
    }

    // ─── Private helpers ──────────────────────────────────────────────────────────

    private long getPropertyLong(String property) {
        try {
            String value = db.getProperty(property);
            if (value != null && !value.isEmpty()) {
                return Long.parseLong(value.trim());
            }
        } catch (RocksDBException | NumberFormatException e) {
            logger.trace("Failed to read property '{}' for {}: {}", property, dbName, e.getMessage());
        }
        return -1L;
    }
}
