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
 */
public interface RocksDbStatsMBean {

    long getBlockCacheUsageBytes();

    long getBlockCachePinnedUsageBytes();

    long getEstimateTableReadersMem();

    long getCurSizeAllMemTables();

    long getCurSizeActiveMemTable();

    long getEstimatePendingCompactionBytes();

    long getEstimateLiveDataSize();

    long getEstimateNumKeys();

    @Deprecated
    long getNumSstFiles();

    long getTotalSstFilesSizeBytes();

    int getDbInstanceCount();
}

