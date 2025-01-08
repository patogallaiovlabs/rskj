package co.rsk.core.bc;

import co.rsk.core.BlockDifficulty;
import co.rsk.core.Coin;
import co.rsk.remasc.Remasc;
import co.rsk.remasc.Sibling;
import org.ethereum.core.Block;
import org.ethereum.core.BlockHeader;
import org.ethereum.util.FastByteComparisons;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.math.BigInteger;
import java.util.List;

public class SelectionRule {

    private static final Logger logger = LoggerFactory.getLogger(Remasc.class);
    private static final int BYTE_ARRAY_OFFSET = 0;
    private static final int BYTE_ARRAY_LENGTH = 32;

    private static final BigInteger PAID_FEES_MULTIPLIER_CRITERIA = BigInteger.valueOf(2);

    public static boolean shouldWeAddThisBlock(
            BlockDifficulty blockDifficulty,
            BlockDifficulty currentDifficulty,
            Block block,
            Block currentBlock) {

        int compareDifficulties = blockDifficulty.compareTo(currentDifficulty);

        if (compareDifficulties > 0) {
            return true;
        }

        if (compareDifficulties < 0) {
            return false;
        }

        Coin pfm = currentBlock.getHeader().getPaidFees().multiply(PAID_FEES_MULTIPLIER_CRITERIA);
        // fees over PAID_FEES_MULTIPLIER_CRITERIA times higher
        if (block.getHeader().getPaidFees().compareTo(pfm) > 0) {
            return true;
        }

        Coin blockFeesCriteria = block.getHeader().getPaidFees().multiply(PAID_FEES_MULTIPLIER_CRITERIA);

        // As a last resort, choose the block with the lower hash. We ask that
        // the fees are at least bigger than the half of current block.
        return currentBlock.getHeader().getPaidFees().compareTo(blockFeesCriteria) < 0 &&
                isThisBlockHashSmaller(block.getHash().getBytes(), currentBlock.getHash().getBytes());
    }
    
    public static boolean isBrokenSelectionRule(
            BlockHeader processingBlockHeader, List<Sibling> siblings) {
        int maxUncleCount = 0;
        long number = processingBlockHeader.getNumber();
        String coinbase = processingBlockHeader.getCoinbase().toHexString();
        for (Sibling sibling : siblings) {
            maxUncleCount = Math.max(maxUncleCount, sibling.getUncleCount());
            Coin pfm = processingBlockHeader.getPaidFees().multiply(PAID_FEES_MULTIPLIER_CRITERIA);
            String siblingCoinbase = sibling.getCoinbase().toHexString();
            if (sibling.getPaidFees().compareTo(pfm) > 0) {
                logger.debug("SIMULATION - [{},{},{}] Broken selection rule: fees over {} times higher. pfm: {} , sibling: {}", number, coinbase, siblingCoinbase, PAID_FEES_MULTIPLIER_CRITERIA, pfm, sibling.getPaidFees());
                return true;
            }
            Coin blockFeesCriteria = sibling.getPaidFees().multiply(PAID_FEES_MULTIPLIER_CRITERIA);
            if (processingBlockHeader.getPaidFees().compareTo(blockFeesCriteria) < 0 &&
                    isThisBlockHashSmaller(sibling.getHash(), processingBlockHeader.getHash().getBytes())) {
                logger.debug("SIMULATION - [{},{},{}] Broken selection rule: fees over {} times higher. blockFeesCriteria: {} , sibling: {}", number, coinbase, siblingCoinbase, PAID_FEES_MULTIPLIER_CRITERIA, blockFeesCriteria, processingBlockHeader.getPaidFees());
                return true;
            }
            if(maxUncleCount > processingBlockHeader.getUncleCount()) {
                logger.debug("SIMULATION - [{},{},{}] Broken selection rule: maxUncleCount > processingBlockHeader.getUncleCount(). maxUncleCount: {} , processingBlockHeader.getUncleCount(): {}", number, coinbase, siblingCoinbase, maxUncleCount, processingBlockHeader.getUncleCount());
            }
        }
        return (maxUncleCount > processingBlockHeader.getUncleCount());
    }

    public static boolean isThisBlockHashSmaller(byte[] thisBlockHash, byte[] compareBlockHash) {
        return FastByteComparisons.compareTo(
                thisBlockHash, BYTE_ARRAY_OFFSET, BYTE_ARRAY_LENGTH,
                compareBlockHash, BYTE_ARRAY_OFFSET, BYTE_ARRAY_LENGTH) < 0;
    }
}
