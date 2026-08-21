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

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Assumptions;

import javax.management.MBeanServer;
import javax.management.ObjectName;
import java.lang.management.ManagementFactory;

import static org.junit.jupiter.api.Assertions.assertEquals;

class BlockProcessingStatsTest {

    private static final String OBJECT_NAME = "co.rsk.metrics:type=BlockProcessingStats";

    @Test
    void mBeanIsRegisteredAndReflectsRecordedValues() throws Exception {
        Assumptions.assumeTrue(BlockProcessingStats.ENABLED, "skipped: -Dblockprocessingstats.enabled=false");

        MBeanServer mBeanServer = ManagementFactory.getPlatformMBeanServer();
        ObjectName objectName = new ObjectName(OBJECT_NAME);

        assertEquals(true, mBeanServer.isRegistered(objectName));

        BlockProcessingStats.recordConnect(123L, 24_500_000L, 42, 111L, 22L, 3L, 4L, 5L, 6L, 7L);
        BlockProcessingStats.recordExecute(55L, 33L, 11L);

        assertEquals(123L, mBeanServer.getAttribute(objectName, "BlockNumber"));
        assertEquals(24_500_000L, mBeanServer.getAttribute(objectName, "GasUsed"));
        assertEquals(42, mBeanServer.getAttribute(objectName, "TxCount"));
        assertEquals(111L, mBeanServer.getAttribute(objectName, "TotalMs"));
        assertEquals(22L, mBeanServer.getAttribute(objectName, "InitialValidationMs"));
        assertEquals(3L, mBeanServer.getAttribute(objectName, "StateRootRegisterMs"));
        assertEquals(4L, mBeanServer.getAttribute(objectName, "SwitchChainMs"));
        assertEquals(5L, mBeanServer.getAttribute(objectName, "SaveReceiptsMs"));
        assertEquals(6L, mBeanServer.getAttribute(objectName, "OnBestBlockMs"));
        assertEquals(7L, mBeanServer.getAttribute(objectName, "OnBlockMs"));
        assertEquals(55L, mBeanServer.getAttribute(objectName, "ExecuteMs"));
        assertEquals(33L, mBeanServer.getAttribute(objectName, "TxExecutionMs"));
        assertEquals(11L, mBeanServer.getAttribute(objectName, "StatePersistMs"));
    }
}
