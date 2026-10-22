package co.rsk.core.bc;

import co.rsk.core.RskAddress;
import org.ethereum.core.Block;
import org.ethereum.core.ImmutableTransaction;
import org.ethereum.core.Transaction;
import org.ethereum.crypto.ECKey;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

import java.math.BigInteger;
import java.util.ArrayList;
import java.util.List;

import static org.mockito.Mockito.*;

class BlockValidatorImplTxHashPrefetchTest {

    private static List<Transaction> signedTxs(int n) {
        ECKey key = new ECKey();
        List<Transaction> txs = new ArrayList<>();
        for (int i = 0; i < n; i++) {
            Transaction tx = Transaction.builder()
                    .nonce(BigInteger.valueOf(i))
                    .gasPrice(BigInteger.ONE)
                    .gasLimit(BigInteger.valueOf(21000 + i))
                    .destination(new byte[20])
                    .chainId((byte) 33)
                    .value(BigInteger.TEN)
                    .data(new byte[i * 37])
                    .build();
            tx.sign(key.getPrivKeyBytes());
            txs.add(tx);
        }
        return txs;
    }

    private static Block blockWith(List<Transaction> txs) {
        Block block = mock(Block.class);
        when(block.getTransactionsList()).thenReturn(txs);
        return block;
    }

    @Test
    void prefetchedHashesAndSendersMatchSerialComputation() {
        List<Transaction> txs = signedTxs(50);
        List<Transaction> copies = new ArrayList<>();
        for (Transaction tx : txs) {
            copies.add(new ImmutableTransaction(tx.getEncoded().clone()));
        }

        BlockValidatorImpl.prefetchSenders(blockWith(txs));

        for (int i = 0; i < txs.size(); i++) {
            Assertions.assertEquals(copies.get(i).getHash(), txs.get(i).getHash());
            Assertions.assertArrayEquals(copies.get(i).getEncoded(), txs.get(i).getEncoded());
            Assertions.assertEquals(copies.get(i).getSender(), txs.get(i).getSender());
        }
    }

    @Test
    void duplicatesAndNullsAreHandled() {
        List<Transaction> txs = signedTxs(10);
        List<Transaction> withDupes = new ArrayList<>(txs);
        withDupes.addAll(txs);
        withDupes.add(null);

        BlockValidatorImpl.prefetchSenders(blockWith(withDupes));

        for (Transaction tx : txs) {
            Assertions.assertEquals(new ImmutableTransaction(tx.getEncoded().clone()).getHash(), tx.getHash());
        }
    }

    @Test
    void hashIsWarmedOncePerDistinctTxAndNotForSmallBlocks() {
        List<Transaction> mocks = new ArrayList<>();
        for (int i = 0; i < 10; i++) {
            Transaction tx = mock(Transaction.class);
            when(tx.getSender()).thenReturn(RskAddress.nullAddress());
            mocks.add(tx);
        }
        List<Transaction> withDupes = new ArrayList<>(mocks);
        withDupes.addAll(mocks);
        BlockValidatorImpl.prefetchSenders(blockWith(withDupes));
        for (Transaction tx : mocks) {
            verify(tx, times(1)).getHash();
            verify(tx, times(1)).getSender();
        }

        List<Transaction> small = new ArrayList<>(mocks.subList(0, 3));
        clearInvocations(small.toArray());
        BlockValidatorImpl.prefetchSenders(blockWith(small));
        for (Transaction tx : small) {
            verify(tx, never()).getHash();
        }
    }

    @Test
    void hashFailureIsSwallowedAndSenderStillPrefetched() {
        List<Transaction> txs = new ArrayList<>(signedTxs(12));
        Transaction bad = mock(Transaction.class);
        when(bad.getHash()).thenThrow(new RuntimeException("malformed"));
        when(bad.getSender()).thenReturn(RskAddress.nullAddress());
        txs.add(5, bad);

        Assertions.assertDoesNotThrow(() -> BlockValidatorImpl.prefetchSenders(blockWith(txs)));
        verify(bad, times(1)).getSender();
    }
}
