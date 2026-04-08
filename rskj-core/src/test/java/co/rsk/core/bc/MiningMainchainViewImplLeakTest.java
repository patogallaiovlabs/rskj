package co.rsk.core.bc;

import co.rsk.crypto.Keccak256;
import org.ethereum.TestUtils;
import org.ethereum.core.Block;
import org.ethereum.core.BlockHeader;
import org.ethereum.db.BlockStore;
import org.junit.jupiter.api.Test;

import java.util.Collections;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.is;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class MiningMainchainViewImplLeakTest {

    @Test
    void testJumpLeakFix() {
        BlockStore blockStore = mock(BlockStore.class);
        Block genesis = createBlock(0, null);
        when(blockStore.getBestBlock()).thenReturn(genesis);

        // Height is 10
        MiningMainchainViewImpl view = new MiningMainchainViewImpl(blockStore, 10);

        // Initial state: only genesis (0)
        assertThat(view.getBlockHashesByNumberSize(), is(1));
        assertThat(view.getBlocksByHashSize(), is(1));

        // Simulating a jump: add block 100
        Block block100 = createBlock(100, TestUtils.generateHash("99"));
        view.addBest(block100.getHeader());

        // Boundary is 100 - 10 = 90.
        // Blocks <= 90 should be deleted.
        // Genesis (0) should be deleted.
        // blockHashesByNumber should only contain 100.
        assertThat(view.getBlockHashesByNumberSize(), is(1));
        assertThat(view.getBlocksByHashSize(), is(1));

        // Simulating another jump: add block 200
        Block block200 = createBlock(200, TestUtils.generateHash("199"));
        view.addBest(block200.getHeader());

        // Boundary is 200 - 10 = 190.
        // Block 100 should be deleted.
        assertThat(view.getBlockHashesByNumberSize(), is(1));
        assertThat(view.getBlocksByHashSize(), is(1));
    }

    private Block createBlock(long number, Keccak256 parentHash) {
        BlockHeader header = mock(BlockHeader.class);
        when(header.getNumber()).thenReturn(number);
        when(header.isGenesis()).thenReturn(number == 0);
        Keccak256 hash = TestUtils.generateHash("hash" + number);
        when(header.getHash()).thenReturn(hash);
        when(header.getParentHash()).thenReturn(parentHash);

        Block block = mock(Block.class);
        when(block.getHeader()).thenReturn(header);
        when(block.getNumber()).thenReturn(number);
        when(block.getHash()).thenReturn(hash);
        when(block.getParentHash()).thenReturn(parentHash);

        return block;
    }
}
