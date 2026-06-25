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

import co.rsk.core.bc.ConsensusValidationMainchainView;
import co.rsk.net.Peer;
import co.rsk.scoring.EventType;
import co.rsk.validators.BlockHeaderValidationRule;
import org.ethereum.TestUtils;
import org.ethereum.core.BlockHeader;
import org.ethereum.core.BlockIdentifier;
import org.ethereum.crypto.HashUtil;
import org.ethereum.validator.DependentBlockHeaderRule;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.Mockito;

import java.util.*;

import static org.mockito.Mockito.*;

class DownloadingHeadersSyncStateTest {

    private static SyncConfiguration parallelConfig(int maxConcurrentHeaderRequests) {
        return new SyncConfiguration(10, 10, 30, 5, 20, 192, 20, 10, 0,
                false, false, 0, Collections.emptyList(), maxConcurrentHeaderRequests);
    }

    private static List<BlockIdentifier> skeletonOf(byte[]... hashesByNumber) {
        List<BlockIdentifier> skeleton = new ArrayList<>();
        for (long number = 0; number < hashesByNumber.length; number++) {
            skeleton.add(new BlockIdentifier(hashesByNumber[(int) number], number));
        }
        return skeleton;
    }

    private static BlockHeader headerWithHash(byte[] hash) {
        BlockHeader header = mock(BlockHeader.class, Mockito.RETURNS_DEEP_STUBS);
        when(header.getHash().getBytes()).thenReturn(hash);
        return header;
    }

    @Test
    void itIgnoresNewPeerInformation() {
        SyncConfiguration syncConfiguration = SyncConfiguration.DEFAULT;
        SimpleSyncEventsHandler syncEventsHandler = new SimpleSyncEventsHandler();
        Map<Peer, List<BlockIdentifier>> skeletons = Collections.singletonMap(null, null);
        SyncState syncState = new DownloadingHeadersSyncState(
                syncConfiguration,
                syncEventsHandler,
                mock(PeersInformation.class),
                mock(ConsensusValidationMainchainView.class),
                mock(DependentBlockHeaderRule.class),
                mock(BlockHeaderValidationRule.class),
                mock(Peer.class), skeletons,
                0);

        for (int i = 0; i < 10; i++) {
            syncState.newPeerStatus();
            Assertions.assertFalse(syncEventsHandler.stopSyncingWasCalled());
        }
    }

    @Test
    void itTimeoutsWhenWaitingForRequest() {
        SyncConfiguration syncConfiguration = SyncConfiguration.DEFAULT;
        SimpleSyncEventsHandler syncEventsHandler = new SimpleSyncEventsHandler();
        Peer selectedPeer = mock(Peer.class);

        byte[] hash0 = TestUtils.generateBytes(getClass(), "h0", 32);
        byte[] hash1 = TestUtils.generateBytes(getClass(), "h1", 32);
        Map<Peer, List<BlockIdentifier>> skeletons =
                Collections.singletonMap(selectedPeer, skeletonOf(hash0, hash1));

        SyncState syncState = new DownloadingHeadersSyncState(
                syncConfiguration,
                syncEventsHandler,
                mock(PeersInformation.class),
                mock(ConsensusValidationMainchainView.class),
                mock(DependentBlockHeaderRule.class),
                mock(BlockHeaderValidationRule.class),
                selectedPeer, skeletons,
                0);

        syncState.onEnter();
        Assertions.assertFalse(syncEventsHandler.stopSyncingWasCalled());

        syncState.tick(syncConfiguration.getTimeoutWaitingRequest().dividedBy(2));
        Assertions.assertFalse(syncEventsHandler.stopSyncingWasCalled());

        syncState.tick(syncConfiguration.getTimeoutWaitingRequest());
        Assertions.assertTrue(syncEventsHandler.stopSyncingWasCalled());
    }

    @Test
    void itDoesntTimeoutBeforeReachingTheLimit() {
        SyncConfiguration syncConfiguration = SyncConfiguration.DEFAULT;
        SimpleSyncEventsHandler syncEventsHandler = new SimpleSyncEventsHandler();
        Peer selectedPeer = mock(Peer.class);

        byte[] hash0 = TestUtils.generateBytes(getClass(), "h0", 32);
        byte[] hash1 = TestUtils.generateBytes(getClass(), "h1", 32);
        Map<Peer, List<BlockIdentifier>> skeletons =
                Collections.singletonMap(selectedPeer, skeletonOf(hash0, hash1));

        SyncState syncState = new DownloadingHeadersSyncState(
                syncConfiguration,
                syncEventsHandler,
                mock(PeersInformation.class),
                mock(ConsensusValidationMainchainView.class),
                mock(DependentBlockHeaderRule.class),
                mock(BlockHeaderValidationRule.class),
                selectedPeer, skeletons,
                0);

        syncState.onEnter();

        syncState.tick(syncConfiguration.getTimeoutWaitingRequest().dividedBy(4));

        Assertions.assertFalse(syncEventsHandler.stopSyncingWasCalled());
    }

    @Test
    void unsolicitedResponseIsIgnored() {
        SyncConfiguration syncConfiguration = SyncConfiguration.DEFAULT;
        SyncEventsHandler syncEventsHandler = mock(SyncEventsHandler.class);
        Peer selectedPeer = mock(Peer.class);
        Peer otherPeer = mock(Peer.class);

        byte[] hash0 = TestUtils.generateBytes(getClass(), "h0", 32);
        byte[] hash1 = TestUtils.generateBytes(getClass(), "h1", 32);
        Map<Peer, List<BlockIdentifier>> skeletons =
                Collections.singletonMap(selectedPeer, skeletonOf(hash0, hash1));

        DownloadingHeadersSyncState syncState = new DownloadingHeadersSyncState(
                syncConfiguration,
                syncEventsHandler,
                mock(PeersInformation.class),
                mock(ConsensusValidationMainchainView.class),
                mock(DependentBlockHeaderRule.class),
                mock(BlockHeaderValidationRule.class),
                selectedPeer, skeletons,
                0);

        // a response from a peer that has no in-flight request must be ignored, not abort the sync
        syncState.newBlockHeaders(otherPeer, Collections.singletonList(headerWithHash(hash1)));

        verify(syncEventsHandler, never()).onErrorSyncing(any(), any(), anyString(), any());
        verify(syncEventsHandler, never()).onSyncIssue(any(), anyString(), any());
        verify(syncEventsHandler, never()).startDownloadingBodies(any(), any(), any());
    }

    @Test
    void newBlockHeadersWhenUnexpectedChunkSizeThenInvalidMessage() {
        SyncConfiguration syncConfiguration = SyncConfiguration.DEFAULT;
        SyncEventsHandler syncEventsHandler = mock(SyncEventsHandler.class);
        Peer selectedPeer = mock(Peer.class);

        byte[] hash0 = TestUtils.generateBytes(getClass(), "h0", 32);
        byte[] hash1 = TestUtils.generateBytes(getClass(), "h1", 32);
        Map<Peer, List<BlockIdentifier>> skeletons =
                Collections.singletonMap(selectedPeer, skeletonOf(hash0, hash1));

        DownloadingHeadersSyncState syncState = new DownloadingHeadersSyncState(
                syncConfiguration,
                syncEventsHandler,
                mock(PeersInformation.class),
                mock(ConsensusValidationMainchainView.class),
                mock(DependentBlockHeaderRule.class),
                mock(BlockHeaderValidationRule.class),
                selectedPeer, skeletons,
                0);

        syncState.onEnter(); // assigns chunk #1 (count == 1) to the selected peer

        // chunk with two headers, but the expected count is 1
        List<BlockHeader> chunk = Arrays.asList(headerWithHash(hash1), headerWithHash(hash0));
        syncState.newBlockHeaders(selectedPeer, chunk);

        verify(syncEventsHandler, times(1)).onErrorSyncing(selectedPeer, EventType.INVALID_MESSAGE,
                "Unexpected chunk size received on {}: hash: {}",
                DownloadingHeadersSyncState.class, HashUtil.toPrintableHash(hash1));
    }

    @Test
    void newBlockHeadersWhenUnexpectedHeaderThenInvalidMessage() {
        SyncConfiguration syncConfiguration = SyncConfiguration.DEFAULT;
        SyncEventsHandler syncEventsHandler = mock(SyncEventsHandler.class);
        Peer selectedPeer = mock(Peer.class);

        byte[] hash0 = TestUtils.generateBytes(getClass(), "h0", 32);
        byte[] hash1 = TestUtils.generateBytes(getClass(), "h1", 32);
        byte[] wrongHash = TestUtils.generateBytes(getClass(), "wrong", 32);
        Map<Peer, List<BlockIdentifier>> skeletons =
                Collections.singletonMap(selectedPeer, skeletonOf(hash0, hash1));

        DownloadingHeadersSyncState syncState = new DownloadingHeadersSyncState(
                syncConfiguration,
                syncEventsHandler,
                mock(PeersInformation.class),
                mock(ConsensusValidationMainchainView.class),
                mock(DependentBlockHeaderRule.class),
                mock(BlockHeaderValidationRule.class),
                selectedPeer, skeletons,
                0);

        syncState.onEnter(); // assigns chunk #1 (count == 1, expected hash == hash1)

        // chunk of the expected size but with a header that does not match the skeleton boundary
        List<BlockHeader> chunk = Collections.singletonList(headerWithHash(wrongHash));
        syncState.newBlockHeaders(selectedPeer, chunk);

        verify(syncEventsHandler, times(1)).onErrorSyncing(selectedPeer, EventType.INVALID_MESSAGE,
                "Unexpected chunk header hash received on {}: hash: {}",
                DownloadingHeadersSyncState.class, HashUtil.toPrintableHash(hash1));
    }

    @Test
    void parallelDownloadFansOutToMultiplePeers() {
        SyncConfiguration syncConfiguration = parallelConfig(3);
        SyncEventsHandler syncEventsHandler = mock(SyncEventsHandler.class);
        PeersInformation peersInformation = mock(PeersInformation.class);
        Peer selectedPeer = mock(Peer.class);
        Peer helperPeer = mock(Peer.class);
        when(peersInformation.getBestPeerCandidates()).thenReturn(Arrays.asList(selectedPeer, helperPeer));

        byte[] hash0 = TestUtils.generateBytes(getClass(), "h0", 32);
        byte[] hash1 = TestUtils.generateBytes(getClass(), "h1", 32);
        byte[] hash2 = TestUtils.generateBytes(getClass(), "h2", 32);
        Map<Peer, List<BlockIdentifier>> skeletons =
                Collections.singletonMap(selectedPeer, skeletonOf(hash0, hash1, hash2));

        DownloadingHeadersSyncState syncState = new DownloadingHeadersSyncState(
                syncConfiguration,
                syncEventsHandler,
                peersInformation,
                mock(ConsensusValidationMainchainView.class),
                mock(DependentBlockHeaderRule.class),
                mock(BlockHeaderValidationRule.class),
                selectedPeer, skeletons,
                0);

        syncState.onEnter();

        // two chunks, two peers: both requests must be dispatched concurrently
        verify(syncEventsHandler, times(1)).sendBlockHeadersRequest(eq(selectedPeer), any(ChunkDescriptor.class));
        verify(syncEventsHandler, times(1)).sendBlockHeadersRequest(eq(helperPeer), any(ChunkDescriptor.class));
    }

    @Test
    void parallelDownloadReassemblesChunksInSkeletonOrder() {
        SyncConfiguration syncConfiguration = parallelConfig(3);
        SyncEventsHandler syncEventsHandler = mock(SyncEventsHandler.class);
        PeersInformation peersInformation = mock(PeersInformation.class);
        Peer selectedPeer = mock(Peer.class);
        Peer helperPeer = mock(Peer.class);
        when(peersInformation.getBestPeerCandidates()).thenReturn(Arrays.asList(selectedPeer, helperPeer));

        byte[] hash0 = TestUtils.generateBytes(getClass(), "h0", 32);
        byte[] hash1 = TestUtils.generateBytes(getClass(), "h1", 32);
        byte[] hash2 = TestUtils.generateBytes(getClass(), "h2", 32);
        Map<Peer, List<BlockIdentifier>> skeletons =
                Collections.singletonMap(selectedPeer, skeletonOf(hash0, hash1, hash2));

        DownloadingHeadersSyncState syncState = new DownloadingHeadersSyncState(
                syncConfiguration,
                syncEventsHandler,
                peersInformation,
                mock(ConsensusValidationMainchainView.class),
                mock(DependentBlockHeaderRule.class),
                mock(BlockHeaderValidationRule.class),
                selectedPeer, skeletons,
                0);

        syncState.onEnter(); // selectedPeer -> chunk #1 (hash1), helperPeer -> chunk #2 (hash2)

        BlockHeader header1 = headerWithHash(hash1);
        BlockHeader header2 = headerWithHash(hash2);

        // deliver the higher chunk first (out of order)
        syncState.newBlockHeaders(helperPeer, Collections.singletonList(header2));
        verify(syncEventsHandler, never()).startDownloadingBodies(any(), any(), any());

        // then the lower chunk
        syncState.newBlockHeaders(selectedPeer, Collections.singletonList(header1));

        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<Deque<BlockHeader>>> captor = ArgumentCaptor.forClass(List.class);
        verify(syncEventsHandler, times(1)).startDownloadingBodies(captor.capture(), eq(skeletons), eq(selectedPeer));

        List<Deque<BlockHeader>> pendingHeaders = captor.getValue();
        Assertions.assertEquals(2, pendingHeaders.size());
        Assertions.assertSame(header1, pendingHeaders.get(0).getFirst());
        Assertions.assertSame(header2, pendingHeaders.get(1).getFirst());
    }

    @Test
    void helperPeerTimeoutIsReassignedWithoutAbortingSync() {
        SyncConfiguration syncConfiguration = parallelConfig(3);
        SyncEventsHandler syncEventsHandler = mock(SyncEventsHandler.class);
        PeersInformation peersInformation = mock(PeersInformation.class);
        Peer selectedPeer = mock(Peer.class);
        Peer helperPeer = mock(Peer.class);
        when(peersInformation.getBestPeerCandidates()).thenReturn(Arrays.asList(selectedPeer, helperPeer));

        byte[] hash0 = TestUtils.generateBytes(getClass(), "h0", 32);
        byte[] hash1 = TestUtils.generateBytes(getClass(), "h1", 32);
        byte[] hash2 = TestUtils.generateBytes(getClass(), "h2", 32);
        Map<Peer, List<BlockIdentifier>> skeletons =
                Collections.singletonMap(selectedPeer, skeletonOf(hash0, hash1, hash2));

        DownloadingHeadersSyncState syncState = new DownloadingHeadersSyncState(
                syncConfiguration,
                syncEventsHandler,
                peersInformation,
                mock(ConsensusValidationMainchainView.class),
                mock(DependentBlockHeaderRule.class),
                mock(BlockHeaderValidationRule.class),
                selectedPeer, skeletons,
                0);

        syncState.onEnter(); // selectedPeer -> chunk #1, helperPeer -> chunk #2

        // selectedPeer completes its chunk so it becomes idle again
        syncState.newBlockHeaders(selectedPeer, Collections.singletonList(headerWithHash(hash1)));

        // helperPeer never answers and times out
        syncState.tick(syncConfiguration.getTimeoutWaitingRequest().plusSeconds(1));

        verify(peersInformation, times(1)).reportEventToPeerScoring(eq(helperPeer), eq(EventType.TIMEOUT_MESSAGE),
                anyString(), any());
        verify(syncEventsHandler, never()).onErrorSyncing(any(), any(), anyString(), any());
        // chunk #2 must be re-requested from the still-trusted selected peer
        verify(syncEventsHandler, times(2)).sendBlockHeadersRequest(eq(selectedPeer), any(ChunkDescriptor.class));
    }
}
