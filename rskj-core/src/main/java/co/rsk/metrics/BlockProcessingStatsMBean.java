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

    long getInitialValidationMs();

    long getExecuteMs();

    long getTxExecutionMs();

    long getStatePersistMs();

    long getStateRootRegisterMs();

    long getSaveReceiptsMs();

    long getOnBestBlockMs();

    long getOnBlockMs();

    long getSwitchChainMs();
}
