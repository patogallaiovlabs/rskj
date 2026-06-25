/*
 * This file is part of RskJ
 * Copyright (C) 2017 RSK Labs Ltd.
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

import java.time.Duration;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

import org.ethereum.core.BlockHeader;
import org.ethereum.core.BlockIdentifier;

import org.ethereum.crypto.HashUtil;

import org.ethereum.util.ByteUtil;
import org.ethereum.validator.DependentBlockHeaderRule;

import com.google.common.annotations.VisibleForTesting;

import co.rsk.core.bc.ConsensusValidationMainchainView;
import co.rsk.crypto.Keccak256;
import co.rsk.net.Peer;
import co.rsk.scoring.EventType;
import co.rsk.validators.BlockHeaderValidationRule;

/**
 * Downloads the block headers described by the skeleton retrieved from the trusted (selected) peer.
 *
 * <p>The headers are split into chunks, one per skeleton link. Every chunk is fully validated
 * against the trusted skeleton (boundary hash, expected size and intra-chunk parent linkage), so a
 * chunk is correct regardless of which peer served it. This lets us request several chunks
 * concurrently from different peers (up to {@code sync.maxConcurrentHeaderRequests}) and reassemble
 * them in skeleton order once all of them have arrived.</p>
 *
 * <p>Failure handling keeps the trust model intact: if the trusted peer misbehaves or times out the
 * whole sync is aborted (as before). If a helper peer returns invalid data or times out, it is
 * penalized and discarded and its chunk is re-queued to be downloaded by another peer, without
 * aborting the sync. Setting {@code maxConcurrentHeaderRequests = 1} reproduces the legacy
 * sequential, single-peer behavior.</p>
 */
public class DownloadingHeadersSyncState extends BaseSelectedPeerSyncState {

    private final Map<Peer, List<BlockIdentifier>> skeletons;
    private final ChunksDownloadHelper chunksDownloadHelper;
    private final DependentBlockHeaderRule blockParentValidationRule;
    private final BlockHeaderValidationRule blockHeaderValidationRule;
    private final PeersInformation peersInformation;
    private final Map<Keccak256, BlockHeader> pendingHeadersByHash;

    // Maximum number of header-chunk requests allowed to be in flight at the same time.
    private final int maxConcurrentRequests;

    // Chunk descriptors keyed by 1-based skeleton link index. Lower index == lower block numbers.
    private final Map<Integer, ChunkDescriptor> chunkDescriptors;
    private final int chunkCount;

    // Validated chunks keyed by chunk index. Each deque is ordered ascending: [lowest .. highest].
    private final Map<Integer, Deque<BlockHeader>> completedChunks;
    // Chunk indexes that still need to be requested (FIFO).
    private final Deque<Integer> chunksToRequest;
    // In-flight requests: peer -> chunk index it is currently downloading (at most one per peer).
    private final Map<Peer, Integer> chunkByPeer;
    // Per in-flight request elapsed time, used for independent timeouts.
    private final Map<Peer, Duration> timeElapsedByPeer;
    // Helper peers that misbehaved or timed out and must not be used again in this session.
    private final Set<Peer> discardedPeers;

    public DownloadingHeadersSyncState(
            SyncConfiguration syncConfiguration,
            SyncEventsHandler syncEventsHandler,
            PeersInformation peersInformation,
            ConsensusValidationMainchainView mainchainView,
            DependentBlockHeaderRule blockParentValidationRule,
            BlockHeaderValidationRule blockHeaderValidationRule,
            Peer peer,
            Map<Peer, List<BlockIdentifier>> skeletons,
            long connectionPoint) {
        super(syncEventsHandler, syncConfiguration, peer);
        this.peersInformation = peersInformation;
        this.blockParentValidationRule = blockParentValidationRule;
        this.blockHeaderValidationRule = blockHeaderValidationRule;
        this.skeletons = skeletons;
        this.chunksDownloadHelper = new ChunksDownloadHelper(
                syncConfiguration,
                skeletons.get(selectedPeer),
                connectionPoint);
        this.pendingHeadersByHash = new ConcurrentHashMap<>();
        this.maxConcurrentRequests = syncConfiguration.getMaxConcurrentHeaderRequests();

        this.chunkDescriptors = new HashMap<>();
        this.chunksToRequest = new ArrayDeque<>();
        this.completedChunks = new HashMap<>();
        this.chunkByPeer = new HashMap<>();
        this.timeElapsedByPeer = new HashMap<>();
        this.discardedPeers = new HashSet<>();

        int index = 0;
        if (skeletons.get(selectedPeer) != null) {
            while (chunksDownloadHelper.hasNextChunk()) {
                chunkDescriptors.put(++index, chunksDownloadHelper.getNextChunk());
                chunksToRequest.addLast(index);
            }
        }
        this.chunkCount = index;

        mainchainView.setPendingHeaders(pendingHeadersByHash);
    }

    @Override
    public void onEnter() {
        assignRequests();
    }

    @Override
    public void newBlockHeaders(Peer peer, List<BlockHeader> chunk) {
        Integer index = chunkByPeer.get(peer);
        if (index == null) {
            // Late, duplicate or unsolicited response (e.g. from a peer whose request already timed
            // out and was reassigned). Ignore it instead of aborting the sync.
            return;
        }

        ChunkDescriptor expectedChunk = chunkDescriptors.get(index);

        boolean unexpectedChunkSize = chunk.isEmpty() || chunk.size() != expectedChunk.getCount();
        if (unexpectedChunkSize) {
            handleInvalidResponse(peer, index, EventType.INVALID_MESSAGE,
                    "Unexpected chunk size received on {}: hash: {}",
                    this.getClass(), HashUtil.toPrintableHash(expectedChunk.getHash()));
            return;
        }

        boolean unexpectedHeader = !ByteUtil.fastEquals(chunk.get(0).getHash().getBytes(), expectedChunk.getHash());
        if (unexpectedHeader) {
            handleInvalidResponse(peer, index, EventType.INVALID_MESSAGE,
                    "Unexpected chunk header hash received on {}: hash: {}",
                    this.getClass(), HashUtil.toPrintableHash(expectedChunk.getHash()));
            return;
        }

        // The headers come ordered by block number desc. Build the chunk bottom-up so the resulting
        // deque is ordered ascending, and validate the parent linkage as we go.
        Deque<BlockHeader> headers = new ArrayDeque<>();
        headers.add(chunk.get(chunk.size() - 1));

        for (int k = 1; k < chunk.size(); ++k) {
            BlockHeader parentHeader = chunk.get(chunk.size() - k);
            BlockHeader header = chunk.get(chunk.size() - k - 1);

            if (!blockHeaderIsValid(header, parentHeader)) {
                handleInvalidResponse(peer, index, EventType.INVALID_HEADER,
                        "Invalid header received on {}, no: {}, hash: {}",
                        this.getClass(), header.getNumber(), header.getPrintableHash());
                return;
            }

            headers.add(header);
        }

        // Chunk fully validated against the trusted skeleton: commit it.
        chunkByPeer.remove(peer);
        timeElapsedByPeer.remove(peer);
        completedChunks.put(index, headers);
        for (BlockHeader header : headers) {
            pendingHeadersByHash.put(header.getHash(), header);
        }

        if (completedChunks.size() == chunkCount) {
            List<Deque<BlockHeader>> pendingHeaders = new ArrayList<>(chunkCount);
            for (int i = 1; i <= chunkCount; i++) {
                pendingHeaders.add(completedChunks.get(i));
            }
            syncEventsHandler.startDownloadingBodies(pendingHeaders, skeletons, selectedPeer);
            return;
        }

        assignRequests();
    }

    @Override
    public void tick(Duration duration) {
        if (chunkByPeer.isEmpty()) {
            return;
        }

        Duration timeout = syncConfiguration.getTimeoutWaitingRequest();
        List<Peer> timedOutPeers = new ArrayList<>();
        for (Peer peer : new ArrayList<>(chunkByPeer.keySet())) {
            Duration elapsed = timeElapsedByPeer.getOrDefault(peer, Duration.ZERO).plus(duration);
            timeElapsedByPeer.put(peer, elapsed);
            if (elapsed.compareTo(timeout) >= 0) {
                timedOutPeers.add(peer);
            }
        }

        for (Peer peer : timedOutPeers) {
            handleTimeout(peer);
        }
    }

    @VisibleForTesting
    public List<BlockIdentifier> getSkeleton() {
        return chunksDownloadHelper.getSkeleton();
    }

    private void assignRequests() {
        for (Peer candidate : candidatePeers()) {
            if (chunksToRequest.isEmpty() || chunkByPeer.size() >= maxConcurrentRequests) {
                break;
            }
            if (chunkByPeer.containsKey(candidate)) {
                continue;
            }

            int index = chunksToRequest.pollFirst();
            chunkByPeer.put(candidate, index);
            timeElapsedByPeer.put(candidate, Duration.ZERO);
            syncEventsHandler.sendBlockHeadersRequest(candidate, chunkDescriptors.get(index));
        }

        // There is pending work, nothing is in flight and no peer can take it: the sync cannot make
        // progress, so fail through the trusted peer to restart the whole process.
        if (!chunksToRequest.isEmpty() && chunkByPeer.isEmpty()) {
            syncEventsHandler.onErrorSyncing(selectedPeer, EventType.TIMEOUT_MESSAGE,
                    "Could not find peers to download headers on {}", this.getClass());
        }
    }

    /**
     * Peers eligible to serve header chunks, ordered by preference. The trusted (selected) peer is
     * always first. Additional best candidates are only used when parallel download is enabled.
     */
    private List<Peer> candidatePeers() {
        List<Peer> result = new ArrayList<>();
        if (!discardedPeers.contains(selectedPeer)) {
            result.add(selectedPeer);
        }

        if (maxConcurrentRequests > 1) {
            for (Peer peer : peersInformation.getBestPeerCandidates()) {
                if (!peer.equals(selectedPeer) && !discardedPeers.contains(peer) && !result.contains(peer)) {
                    result.add(peer);
                }
            }
        }

        return result;
    }

    private void handleTimeout(Peer peer) {
        Integer index = chunkByPeer.remove(peer);
        timeElapsedByPeer.remove(peer);

        if (peer.equals(selectedPeer)) {
            syncEventsHandler.onErrorSyncing(selectedPeer, EventType.TIMEOUT_MESSAGE,
                    "Timeout waiting requests on {}", this.getClass());
            return;
        }

        peersInformation.reportEventToPeerScoring(peer, EventType.TIMEOUT_MESSAGE,
                "Timeout waiting headers on {}", this.getClass());
        discardedPeers.add(peer);
        if (index != null) {
            requeue(index);
        }
        assignRequests();
    }

    private void handleInvalidResponse(Peer peer, int index, EventType eventType, String message, Object... arguments) {
        chunkByPeer.remove(peer);
        timeElapsedByPeer.remove(peer);

        if (peer.equals(selectedPeer)) {
            // The trusted peer is the source of the skeleton; if it misbehaves, abort the whole sync.
            syncEventsHandler.onErrorSyncing(selectedPeer, eventType, message, arguments);
            return;
        }

        // A helper peer returned bad data: penalize it, stop using it and re-queue its chunk.
        peersInformation.reportEventToPeerScoring(peer, eventType, message, arguments);
        discardedPeers.add(peer);
        requeue(index);
        assignRequests();
    }

    private void requeue(int index) {
        if (!completedChunks.containsKey(index)
                && !chunksToRequest.contains(index)
                && !chunkByPeer.containsValue(index)) {
            chunksToRequest.addFirst(index);
        }
    }

    private boolean blockHeaderIsValid(BlockHeader header, BlockHeader parentHeader) {
        if (!parentHeader.getHash().equals(header.getParentHash())) {
            return false;
        }

        if (header.getNumber() != parentHeader.getNumber() + 1) {
            return false;
        }

        if (!blockHeaderValidationRule.isValid(header)) {
            return false;
        }

        return blockParentValidationRule.validate(header, parentHeader);
    }
}
