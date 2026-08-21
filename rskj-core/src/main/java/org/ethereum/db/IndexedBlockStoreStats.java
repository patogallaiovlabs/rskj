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

package org.ethereum.db;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.management.MBeanServer;
import javax.management.MalformedObjectNameException;
import javax.management.ObjectName;
import java.lang.management.ManagementFactory;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Exposes IndexedBlockStore cache metrics over JMX.
 */
public class IndexedBlockStoreStats implements IndexedBlockStoreStatsMBean {

    private static final Logger logger = LoggerFactory.getLogger(IndexedBlockStoreStats.class);
    static final String JMX_DOMAIN = "org.ethereum.db";

    private final ObjectName objectName;
    private final AtomicLong cacheHits = new AtomicLong();
    private final AtomicLong cacheMisses = new AtomicLong();
    private final AtomicLong dbReads = new AtomicLong();

    public IndexedBlockStoreStats(String name) {
        this.objectName = createObjectName(name);
        registerMBean();
    }

    public void recordCacheHit() {
        cacheHits.incrementAndGet();
    }

    public void recordCacheMiss() {
        cacheMisses.incrementAndGet();
    }

    /**
     * A cache miss that actually found the block in the store, i.e. one that cost a
     * deserialization on top of the store read. A miss with no matching record (a
     * true-negative lookup) counts in {@link #recordCacheMiss()} only.
     */
    public void recordDbRead() {
        dbReads.incrementAndGet();
    }

    @Override
    public long getCacheHits() {
        return cacheHits.get();
    }

    @Override
    public long getCacheMisses() {
        return cacheMisses.get();
    }

    @Override
    public long getDbReads() {
        return dbReads.get();
    }

    @Override
    public double getCacheHitRatio() {
        long hits = cacheHits.get();
        long misses = cacheMisses.get();
        long total = hits + misses;
        if (total == 0) {
            return 0d;
        }

        return (double) hits / total;
    }

    private ObjectName createObjectName(String name) {
        try {
            return new ObjectName(String.format("%s:type=IndexedBlockStoreStats,name=%s", JMX_DOMAIN, name));
        } catch (MalformedObjectNameException e) {
            throw new IllegalStateException("Invalid JMX object name for IndexedBlockStoreStats", e);
        }
    }

    private void registerMBean() {
        try {
            MBeanServer mBeanServer = ManagementFactory.getPlatformMBeanServer();
            if (mBeanServer.isRegistered(objectName)) {
                mBeanServer.unregisterMBean(objectName);
            }
            mBeanServer.registerMBean(this, objectName);
        } catch (Exception e) {
            logger.warn("Failed to register IndexedBlockStoreStats MBean: {}", e.getMessage());
        }
    }
}

