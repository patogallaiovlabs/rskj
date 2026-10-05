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

/**
 * JMX MBean interface exposing the same per-block execution/persistence timing breakdown
 * normally only visible via block-breakdown.log's debug lines, as gauges reflecting the
 * most recently connected block.
 */
public interface BlockProcessingStatsMBean {

    long getBlockNumber();

    long getGasUsed();

    int getTxCount();

    long getTotalMs();

    /**
     * Time inside tryToConnect before validation starts: the "does this block already
     * exist" store lookup (a guaranteed miss for any genuinely new block), the accessLock
     * acquisition, and the parent/total-difficulty lookups. Previously unmeasured, and on a
     * node with a large database it was ~22% of TotalMs.
     */
    long getPreambleMs();

    long getInitialValidationMs();

    long getExecuteMs();

    long getPostExecuteValidationMs();

    long getProcessBestMs();

    long getTxExecutionMs();

    long getStatePersistMs();

    long getStateRootRegisterMs();

    long getSaveReceiptsMs();

    long getOnBestBlockMs();

    long getOnBlockMs();

    long getSwitchChainMs();
}
