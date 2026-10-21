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

package co.rsk.core.bc;

import co.rsk.validators.BlockParentDependantValidationRule;
import co.rsk.validators.BlockValidationRule;
import co.rsk.validators.BlockValidator;
import co.rsk.remasc.RemascTransaction;
import org.ethereum.core.Block;
import org.ethereum.core.Transaction;
import org.ethereum.db.BlockStore;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;

/**
 * BlockValidator has methods to validate block content before its execution
 *
 * Created by ajlopez on 29/07/2016.
 */
public class BlockValidatorImpl implements BlockValidator {

    private static final Logger logger = LoggerFactory.getLogger("blocksyncservice");

    private final BlockStore blockStore;

    private final BlockParentDependantValidationRule blockParentValidator;

    private final BlockValidationRule blockValidator;

    public BlockValidatorImpl(BlockStore blockStore, BlockParentDependantValidationRule blockParentValidator, BlockValidationRule blockValidator) {
        this.blockStore = blockStore;
        this.blockParentValidator = blockParentValidator;
        this.blockValidator = blockValidator;
    }

    /**
     * Validate a block.
     * The validation includes
     * - Validate the header data relative to parent block
     * - Validate the transaction root hash to transaction list
     * - Validate uncles
     * - Validate transactions
     *
     * @param block        Block to validate
     * @return true if the block is valid, false if the block is invalid
     */
    @Override
    public boolean isValid(Block block) {
        if (block.isGenesis()) {
            logger.warn("Block is Genesis");
            return false;
        }

        prefetchSenders(block);

        Block parent = getParent(block);

        if(!this.blockParentValidator.isValid(block, parent)) {
            return false;
        }

        if(!this.blockValidator.isValid(block)) {
            return false;
        }

        return true;
    }

    static final int MIN_TXS_FOR_PREFETCH = 8;

    /**
     * Recovers tx senders in parallel, memoizing them in each tx. Recovery is a pure function of the
     * signed payload, so results are identical to the serial path. Failures are ignored here and
     * surface later in the regular validation rules.
     */
    static void prefetchSenders(Block block) {
        List<Transaction> txs = block.getTransactionsList();
        if (txs == null || txs.size() < MIN_TXS_FOR_PREFETCH) {
            return;
        }
        try {
            txs.parallelStream().forEach(tx -> {
                if (tx == null || tx instanceof RemascTransaction) {
                    return;
                }
                try {
                    tx.getSender();
                } catch (RuntimeException e) {
                    // left un-memoized; validation rules handle it as before
                }
            });
        } catch (RuntimeException e) {
            // ignore, the serial path will handle it
        }
    }

    private Block getParent(Block block) {
        if (this.blockStore == null) {
            return null;
        }

        return blockStore.getBlockByHash(block.getParentHash().getBytes());
    }
}
