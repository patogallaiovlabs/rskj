package co.rsk.core.bc;

import co.rsk.core.RskAddress;
import org.ethereum.core.Block;
import org.ethereum.core.Transaction;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.mockito.Mockito.*;

class BlockValidatorImplSenderPrefetchTest {

    private List<Transaction> txs(int n, boolean firstFails) {
        List<Transaction> list = new ArrayList<>();
        for (int i = 0; i < n; i++) {
            Transaction tx = mock(Transaction.class);
            if (firstFails && i == 0) {
                when(tx.getSender()).thenThrow(new RuntimeException("bad"));
            } else {
                when(tx.getSender()).thenReturn(RskAddress.nullAddress());
            }
            list.add(tx);
        }
        return list;
    }

    @Test
    void prefetchesWhenEnoughTxs() {
        List<Transaction> list = txs(10, false);
        Block block = mock(Block.class);
        when(block.getTransactionsList()).thenReturn(list);
        BlockValidatorImpl.prefetchSenders(block);
        for (Transaction tx : list) {
            verify(tx, times(1)).getSender();
        }
    }

    @Test
    void skipsSmallBlocks() {
        List<Transaction> list = txs(3, false);
        Block block = mock(Block.class);
        when(block.getTransactionsList()).thenReturn(list);
        BlockValidatorImpl.prefetchSenders(block);
        for (Transaction tx : list) {
            verify(tx, never()).getSender();
        }
    }

    @Test
    void swallowsFailures() {
        List<Transaction> list = txs(10, true);
        Block block = mock(Block.class);
        when(block.getTransactionsList()).thenReturn(list);
        BlockValidatorImpl.prefetchSenders(block);
        verify(list.get(1), times(1)).getSender();
    }
}
