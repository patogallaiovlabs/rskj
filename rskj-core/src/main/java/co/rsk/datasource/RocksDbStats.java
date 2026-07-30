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

import javax.management.MBeanServer;
import javax.management.MalformedObjectNameException;
import javax.management.ObjectName;
import java.lang.management.ManagementFactory;

/**
 * Exposes RocksDB internal properties as per-database JMX metrics.
 */
public class RocksDbStats implements RocksDbStatsMBean {

    private static final Logger logger = LoggerFactory.getLogger(RocksDbStats.class);

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
            MBeanServer mBeanServer = ManagementFactory.getPlatformMBeanServer();
            if (mBeanServer.isRegistered(objectName)) {
                mBeanServer.unregisterMBean(objectName);
            }
            mBeanServer.registerMBean(this, objectName);
            logger.info("RocksDbStats MBean registered for database: {}", dbName);
        } catch (Exception e) {
            logger.warn("Failed to register RocksDbStats MBean for {}: {}", dbName, e.getMessage());
        }
    }

    public void unregister() {
        try {
            MBeanServer mBeanServer = ManagementFactory.getPlatformMBeanServer();
            if (mBeanServer.isRegistered(objectName)) {
                mBeanServer.unregisterMBean(objectName);
                logger.debug("RocksDbStats MBean unregistered for database: {}", dbName);
            }
        } catch (Exception e) {
            logger.warn("Failed to unregister RocksDbStats MBean for {}: {}", dbName, e.getMessage());
        }
    }

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
        return 1;
    }

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

