package co.rsk.validators;

import co.rsk.blockchain.utils.BlockGenerator;
import co.rsk.core.bc.BlockHashesHelper;
import org.ethereum.config.blockchain.upgrades.ActivationConfig;
import org.ethereum.config.blockchain.upgrades.ActivationConfigsForTest;
import org.ethereum.core.Block;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

class BlockRootValidationRuleTest {

    private final ActivationConfig activationConfig = ActivationConfigsForTest.all();

    @Test
    void validBlockPasses() {
        BlockGenerator generator = new BlockGenerator();
        Block block = generator.createChildBlock(generator.getGenesisBlock(), 3);
        Assertions.assertTrue(new BlockRootValidationRule(activationConfig).isValid(block));
    }

    @Test
    void calculatedRootMatchesHelperForBothFlags() {
        BlockGenerator generator = new BlockGenerator();
        Block block = generator.createChildBlock(generator.getGenesisBlock(), 3);
        block.seal();
        for (boolean flag : new boolean[]{true, false}) {
            Assertions.assertArrayEquals(
                    BlockHashesHelper.getTxTrieRoot(block.getTransactionsList(), flag),
                    block.getCalculatedTxTrieRoot(flag));
        }
    }

    @Test
    void replacedTransactionsListInvalidatesCachedRoot() {
        BlockGenerator generator = new BlockGenerator();
        Block block = generator.createChildBlock(generator.getGenesisBlock(), 3);
        Assertions.assertFalse(block.isSealed());
        block.setTransactionsList(java.util.Collections.emptyList());
        block.seal();
        Assertions.assertArrayEquals(
                BlockHashesHelper.getTxTrieRoot(java.util.Collections.emptyList(), true),
                block.getCalculatedTxTrieRoot(true));
        Assertions.assertFalse(new BlockRootValidationRule(activationConfig).isValid(block));
    }

    @Test
    void mismatchedHeaderRootIsRejected() {
        BlockGenerator generator = new BlockGenerator();
        Block block = generator.createChildBlock(generator.getGenesisBlock(), 3);
        block.getHeader().setTransactionsRoot(new byte[32]);
        Assertions.assertFalse(new BlockRootValidationRule(activationConfig).isValid(block));
    }
}
