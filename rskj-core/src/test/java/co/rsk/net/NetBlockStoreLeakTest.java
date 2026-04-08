package co.rsk.net;

import co.rsk.crypto.Keccak256;
import org.ethereum.TestUtils;
import org.ethereum.core.Block;
import org.ethereum.core.BlockHeader;
import org.junit.jupiter.api.Test;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.lessThanOrEqualTo;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class NetBlockStoreLeakTest {

    @Test
    void testBoundedCaches() {
        NetBlockStore store = new NetBlockStore();

        // Fill headers beyond MAX_HEADERS (10000)
        for (int i = 0; i < 11000; i++) {
            BlockHeader header = mock(BlockHeader.class);
            Keccak256 hash = TestUtils.generateHash("header" + i);
            when(header.getHash()).thenReturn(hash);
            store.saveHeader(header);
        }

        // Should be capped at 10000
        assertThat(store.getHeadersSize(), is(10000));

        // Fill blocks beyond MAX_BLOCKS (5000)
        for (int i = 0; i < 6000; i++) {
            Block block = createBlock(i);
            store.saveBlock(block);
        }

        // Should be capped at 5000
        assertThat(store.getBlocksSize(), is(5000));

        // Verify secondary indices are also cleaned up
        // Note: some blocks might have same number/parent in this simple loop if not
        // careful,
        // but they should definitely not grow to 6000.
        assertThat(store.getBlocksByNumberSize(), lessThanOrEqualTo(5000));
        assertThat(store.getBlocksByParentSize(), lessThanOrEqualTo(5000));
    }

    private Block createBlock(long number) {
        BlockHeader header = mock(BlockHeader.class);
        when(header.getNumber()).thenReturn(number);
        Keccak256 hash = TestUtils.generateHash("blockhash" + number);
        when(header.getHash()).thenReturn(hash);
        Keccak256 parentHash = TestUtils.generateHash("parent" + number);
        when(header.getParentHash()).thenReturn(parentHash);

        Block block = mock(Block.class);
        when(block.getHeader()).thenReturn(header);
        when(block.getNumber()).thenReturn(number);
        when(block.getHash()).thenReturn(hash);
        when(block.getParentHash()).thenReturn(parentHash);

        return block;
    }
}
