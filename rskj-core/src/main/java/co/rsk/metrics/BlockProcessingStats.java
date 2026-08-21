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

package co.rsk.metrics;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.management.MBeanServer;
import javax.management.ObjectName;
import java.lang.management.ManagementFactory;

/**
 * Publishes the per-block execution/persistence timing breakdown (the same figures
 * BlockChainImpl/BlockExecutor log to block-breakdown.log) as a JMX MBean, so the
 * already-deployed JMX-Prometheus javaagent can scrape them into Grafana without needing
 * the debug loggers turned on. Each gauge reflects the most recently connected block --
 * standard sample-on-scrape semantics, same as JVM/GC JMX metrics.
 *
 * A single JVM only ever connects one block at a time, so the plain (non-atomic) fields
 * below are fine: a scrape landing mid-update reads an old-or-new but always internally
 * consistent value for each individual field, and there is no concurrent writer.
 */
public final class BlockProcessingStats implements BlockProcessingStatsMBean {

    private static final Logger logger = LoggerFactory.getLogger("blockprocessingstats");

    private static final String OBJECT_NAME = "co.rsk.metrics:type=BlockProcessingStats";

    /**
     * Sole switch controlling whether BlockChainImpl/BlockExecutor pay the (negligible --
     * a handful of System.nanoTime() calls per block) cost of capturing these timings, and
     * whether this MBean gets registered at all. Defaults on; set
     * -Dblockprocessingstats.enabled=false to disable.
     */
    public static final boolean ENABLED = Boolean.parseBoolean(
            System.getProperty("blockprocessingstats.enabled", "true"));

    private static final BlockProcessingStats INSTANCE = ENABLED ? new BlockProcessingStats() : null;

    private volatile long blockNumber;
    private volatile long gasUsed;
    private volatile int txCount;
    private volatile long totalMs;
    private volatile long initialValidationMs;
    private volatile long stateRootRegisterMs;
    private volatile long switchChainMs;
    private volatile long saveReceiptsMs;
    private volatile long onBestBlockMs;
    private volatile long onBlockMs;
    private volatile long executeMs;
    private volatile long txExecutionMs;
    private volatile long statePersistMs;

    private BlockProcessingStats() {
        try {
            MBeanServer mBeanServer = ManagementFactory.getPlatformMBeanServer();
            ObjectName objectName = new ObjectName(OBJECT_NAME);
            if (mBeanServer.isRegistered(objectName)) {
                mBeanServer.unregisterMBean(objectName);
            }
            mBeanServer.registerMBean(this, objectName);
            logger.info("BlockProcessingStats MBean registered as {}", OBJECT_NAME);
        } catch (Exception e) {
            logger.warn("Failed to register BlockProcessingStats MBean: {}", e.getMessage());
        }
    }

    /**
     * Called once per connected block from BlockChainImpl, with the same values it logs
     * to "block connect breakdown best/alt:".
     */
    public static void recordConnect(
            long blockNumber,
            long gasUsed,
            int txCount,
            long totalMs,
            long initialValidationMs,
            long stateRootRegisterMs,
            long switchChainMs,
            long saveReceiptsMs,
            long onBestBlockMs,
            long onBlockMs) {
        if (INSTANCE == null) {
            return;
        }

        INSTANCE.blockNumber = blockNumber;
        INSTANCE.gasUsed = gasUsed;
        INSTANCE.txCount = txCount;
        INSTANCE.totalMs = totalMs;
        INSTANCE.initialValidationMs = initialValidationMs;
        INSTANCE.stateRootRegisterMs = stateRootRegisterMs;
        INSTANCE.switchChainMs = switchChainMs;
        INSTANCE.saveReceiptsMs = saveReceiptsMs;
        INSTANCE.onBestBlockMs = onBestBlockMs;
        INSTANCE.onBlockMs = onBlockMs;
    }

    /**
     * Called once per executed block from BlockExecutor, with the same values it logs to
     * "block execute breakdown pre-RSKIP144/parallel:".
     */
    public static void recordExecute(long executeMs, long txExecutionMs, long statePersistMs) {
        if (INSTANCE == null) {
            return;
        }

        INSTANCE.executeMs = executeMs;
        INSTANCE.txExecutionMs = txExecutionMs;
        INSTANCE.statePersistMs = statePersistMs;
    }

    @Override
    public long getBlockNumber() {
        return blockNumber;
    }

    @Override
    public long getGasUsed() {
        return gasUsed;
    }

    @Override
    public int getTxCount() {
        return txCount;
    }

    @Override
    public long getTotalMs() {
        return totalMs;
    }

    @Override
    public long getInitialValidationMs() {
        return initialValidationMs;
    }

    @Override
    public long getExecuteMs() {
        return executeMs;
    }

    @Override
    public long getTxExecutionMs() {
        return txExecutionMs;
    }

    @Override
    public long getStatePersistMs() {
        return statePersistMs;
    }

    @Override
    public long getStateRootRegisterMs() {
        return stateRootRegisterMs;
    }

    @Override
    public long getSaveReceiptsMs() {
        return saveReceiptsMs;
    }

    @Override
    public long getOnBestBlockMs() {
        return onBestBlockMs;
    }

    @Override
    public long getOnBlockMs() {
        return onBlockMs;
    }

    @Override
    public long getSwitchChainMs() {
        return switchChainMs;
    }
}
