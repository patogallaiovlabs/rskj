/*
 * This file is part of RskJ
 * Copyright (C) 2017 RSK Labs Ltd.
 * (derived from ethereumJ library, Copyright (c) 2016 <ether.camp>)
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

package org.ethereum.core;

import co.rsk.core.RskAddress;
import co.rsk.remasc.RemascTransaction;
import co.rsk.util.MaxSizeHashMap;

import java.util.Map;

public class ReceivedTxSignatureCache implements ISignatureCache {

    private static final int MAX_CACHE_SIZE = 6000; //Txs in three blocks

    private final Map<Transaction, RskAddress> addressesCache;

    public ReceivedTxSignatureCache() {
        addressesCache = new MaxSizeHashMap<>(MAX_CACHE_SIZE,true);
    }

    @Override
    public RskAddress getSender(Transaction transaction) {

        if (transaction instanceof RemascTransaction) {
            return RemascTransaction.REMASC_ADDRESS;
        }

        return addressesCache.computeIfAbsent(transaction, Transaction::getSender);
    }

    public boolean containsTx(Transaction transaction) {
        return addressesCache.containsKey(transaction);
    }
}