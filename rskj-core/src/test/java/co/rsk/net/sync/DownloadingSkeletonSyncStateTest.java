/*
 * This file is part of RskJ
 * Copyright (C) 2022 RSK Labs Ltd.
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

package co.rsk.net.sync;

import co.rsk.net.NodeID;
import co.rsk.net.Peer;
import co.rsk.scoring.EventType;
import org.ethereum.core.BlockIdentifier;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.net.InetAddress;
import java.net.UnknownHostException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;

import static org.mockito.Mockito.*;
import static org.mockito.Mockito.when;

class DownloadingSkeletonSyncStateTest {

    // TODO Test other logic

    private SyncConfiguration syncConfiguration;
    private SyncEventsHandler syncEventsHandler;
    private PeersInformation peersInformation;
    private Peer selectedPeer;

    @BeforeEach
    void setUp () throws UnknownHostException {
        syncConfiguration = SyncConfiguration.IMMEDIATE_FOR_TESTING;
        syncEventsHandler = mock(SyncEventsHandler.class);
        peersInformation = mock(PeersInformation.class);
        selectedPeer = mock(Peer.class);
        NodeID nodeID = mock(NodeID.class);
        when(selectedPeer.getPeerNodeID()).thenReturn(nodeID);
        when(selectedPeer.getAddress()).thenReturn(InetAddress.getByName("127.0.0.1"));
    }

    @Test
    void onMessageTimeOut() {
        DownloadingSkeletonSyncState target = new DownloadingSkeletonSyncState(
                syncConfiguration,
                syncEventsHandler,
                peersInformation,
                selectedPeer,
                0);

        target.onMessageTimeOut();
        verify(syncEventsHandler, times(1))
                .onErrorSyncing(selectedPeer, EventType.TIMEOUT_MESSAGE,
                        "Timeout waiting requests on {}", DownloadingSkeletonSyncState.class);
    }

    private static List<BlockIdentifier> someSkeleton(String seed) {
        List<BlockIdentifier> skeleton = new ArrayList<>();
        skeleton.add(new BlockIdentifier((seed + "-0").getBytes(), 0));
        skeleton.add(new BlockIdentifier((seed + "-1").getBytes(), 192));
        return skeleton;
    }

    @Test
    void singleCandidateTransitionsAfterFirstSkeleton() {
        when(peersInformation.getBestPeerCandidates()).thenReturn(Arrays.asList(selectedPeer));

        DownloadingSkeletonSyncState target = new DownloadingSkeletonSyncState(
                syncConfiguration, syncEventsHandler, peersInformation, selectedPeer, 0);
        target.onEnter();
        verify(syncEventsHandler, times(1)).sendSkeletonRequest(selectedPeer, 0);

        target.newSkeleton(someSkeleton("a"), selectedPeer);

        verify(syncEventsHandler, times(1)).startDownloadingHeaders(anyMap(), eq(0L), eq(selectedPeer));
    }

    @Test
    void waitsForAllCandidatesBeforeTransitioning() {
        Peer helperPeer = mock(Peer.class);
        when(helperPeer.getPeerNodeID()).thenReturn(mock(NodeID.class));
        when(peersInformation.getBestPeerCandidates()).thenReturn(Arrays.asList(selectedPeer, helperPeer));

        DownloadingSkeletonSyncState target = new DownloadingSkeletonSyncState(
                syncConfiguration, syncEventsHandler, peersInformation, selectedPeer, 0);
        target.onEnter();

        // both candidates were asked for a skeleton
        verify(syncEventsHandler, times(1)).sendSkeletonRequest(selectedPeer, 0);
        verify(syncEventsHandler, times(1)).sendSkeletonRequest(helperPeer, 0);

        // first skeleton arrives: must NOT transition yet
        target.newSkeleton(someSkeleton("helper"), helperPeer);
        verify(syncEventsHandler, never()).startDownloadingHeaders(anyMap(), anyLong(), any());

        // second (and last) skeleton arrives: now it transitions, collecting both skeletons
        target.newSkeleton(someSkeleton("selected"), selectedPeer);

        @SuppressWarnings("unchecked")
        ArgumentCaptor<Map<Peer, List<BlockIdentifier>>> captor = ArgumentCaptor.forClass(Map.class);
        verify(syncEventsHandler, times(1)).startDownloadingHeaders(captor.capture(), eq(0L), eq(selectedPeer));

        Map<Peer, List<BlockIdentifier>> collected = captor.getValue();
        Assertions.assertEquals(2, collected.size());
        Assertions.assertTrue(collected.containsKey(selectedPeer));
        Assertions.assertTrue(collected.containsKey(helperPeer));
    }
}

