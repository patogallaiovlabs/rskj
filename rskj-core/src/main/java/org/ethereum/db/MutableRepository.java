/*
 * This file is part of RskJ
 * Copyright (C) 2019 RSK Labs Ltd.
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

package org.ethereum.db;

import co.rsk.core.Coin;
import co.rsk.core.RskAddress;
import co.rsk.core.bc.IReadWrittenKeysTracker;
import co.rsk.core.types.ints.Uint24;
import co.rsk.crypto.Keccak256;
import co.rsk.db.MutableTrieCache;
import co.rsk.db.MutableTrieImpl;
import co.rsk.trie.IterationElement;
import co.rsk.trie.MutableTrie;
import co.rsk.trie.Trie;
import co.rsk.trie.TrieKeySlice;
import co.rsk.trie.TrieStore;
import co.rsk.trie.TrieStoreImpl;
import com.google.common.annotations.VisibleForTesting;
import org.ethereum.core.AccountState;
import org.ethereum.core.Repository;
import org.ethereum.crypto.HashUtil;
import org.ethereum.crypto.Keccak256Helper;
import org.ethereum.datasource.HashMapDB;
import org.ethereum.vm.DataWord;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import java.math.BigInteger;
import java.util.HashSet;
import java.util.Iterator;
import java.util.Optional;
import java.util.Set;

public class MutableRepository implements Repository {
    private static final Logger logger = LoggerFactory.getLogger("repository");
    private static final byte[] EMPTY_BYTE_ARRAY = new byte[0];
    public static final Keccak256 KECCAK_256_OF_EMPTY_ARRAY = new Keccak256(Keccak256Helper.keccak256(EMPTY_BYTE_ARRAY));
    private static final byte[] ONE_BYTE_ARRAY = new byte[] { 0x01 };

    private final TrieKeyMapper trieKeyMapper;
    private final MutableTrie mutableTrie;
    private MutableTrie transientTrie;
    private final IReadWrittenKeysTracker tracker;

    public MutableRepository(TrieStore trieStore, Trie trie) {
        this(new MutableTrieImpl(trieStore, trie), aInMemoryMutableTrie());
    }

    public MutableRepository(MutableTrie mutableTrie) {
       this(mutableTrie, aInMemoryMutableTrie());
    }

    public MutableRepository(MutableTrie mutableTrie, IReadWrittenKeysTracker tracker) {
        this(mutableTrie, aInMemoryMutableTrie(), tracker);
    }

    private static MutableTrieImpl aInMemoryMutableTrie() {
        return new MutableTrieImpl(new TrieStoreImpl(new HashMapDB()), new Trie());
    }

    public MutableRepository(MutableTrie mutableTrie, MutableTrie transientTrie) {
        this.trieKeyMapper = new TrieKeyMapper();
        this.mutableTrie = mutableTrie;
        this.transientTrie = transientTrie;
        this.tracker = new DummyReadWrittenKeysTracker();
    }

    public MutableRepository(MutableTrie mutableTrie, MutableTrie transientTrie, IReadWrittenKeysTracker tracker) {
        this.trieKeyMapper = new TrieKeyMapper();
        this.mutableTrie = mutableTrie;
        this.transientTrie = transientTrie;
        this.tracker = tracker;
    }

    @Override
    public Trie getTrie() {
        return mutableTrie.getTrie();
    }

    @Override
    public synchronized AccountState createAccount(RskAddress addr) {
        AccountState accountState = new AccountState();
        updateAccountState(addr, accountState);
        return accountState;
    }

    @Override
    public synchronized void setupContract(RskAddress addr) {
        byte[] prefix = trieKeyMapper.getAccountStoragePrefixKey(addr);
        internalPut(prefix, ONE_BYTE_ARRAY);
    }

    @Override
    public synchronized boolean isExist(RskAddress addr) {
        // Here we assume size != 0 means the account exists
        byte[] accountKey = trieKeyMapper.getAccountKey(addr);
        return internalGetValueLength(accountKey).compareTo(Uint24.ZERO) > 0;
    }

    @Override
    public synchronized AccountState getAccountState(RskAddress addr) {
        AccountState result = null;
        byte[] accountData = getAccountData(addr);

        // If there is no account it returns null
        if (accountData != null && accountData.length != 0) {
            result = new AccountState(accountData);
        }
        return result;
    }

    @Override
    public synchronized void delete(RskAddress addr) {
        byte[] accountKey = trieKeyMapper.getAccountKey(addr);
        tracker.addNewWrittenKey(new ByteArrayWrapper(accountKey));
        mutableTrie.deleteRecursive(accountKey);
    }

    @Override
    public synchronized void hibernate(RskAddress addr) {
        AccountState account = getAccountStateOrCreateNew(addr);

        account.hibernate();
        updateAccountState(addr, account);
    }

    @Override
    public void setNonce(RskAddress addr,BigInteger nonce) {
        AccountState account = getAccountStateOrCreateNew(addr);

        account.setNonce(nonce);
        updateAccountState(addr, account);
    }

    @Override
    public synchronized BigInteger increaseNonce(RskAddress addr) {
        AccountState account = getAccountStateOrCreateNew(addr);

        account.incrementNonce();
        updateAccountState(addr, account);
        return account.getNonce();
    }

    @Override
    public synchronized BigInteger getNonce(RskAddress addr) {
        // Why would getNonce create an Account in the repository? The semantic of a get()
        // is clear: do not change anything!
        AccountState account = getAccountState(addr);
        if (account == null) {
            return BigInteger.ZERO;
        }

        return account.getNonce();
    }

    @Override
    public synchronized void saveCode(RskAddress addr, byte[] code) {
        byte[] key = trieKeyMapper.getCodeKey(addr);
        internalPut(key, code);

        if (code != null && code.length != 0 && !isExist(addr)) {
            createAccount(addr);
        }
    }

    @Override
    public synchronized int getCodeLength(RskAddress addr) {
        AccountState account = getAccountState(addr);
        if (account == null || account.isHibernated()) {
            return 0;
        }

        byte[] key = trieKeyMapper.getCodeKey(addr);
        return internalGetValueLength(key).intValue();
    }

    @Override
    public synchronized Keccak256 getCodeHashNonStandard(RskAddress addr) {

        if (!isExist(addr)) {
            return Keccak256.ZERO_HASH;
        }

        if (!isContract(addr)) {
            return KECCAK_256_OF_EMPTY_ARRAY;
        }

        byte[] key = trieKeyMapper.getCodeKey(addr);
        Optional<Keccak256> valueHash = internalGetValueHash(key);

        //Returning ZERO_HASH is the non standard implementation we had pre RSKIP169 implementation
        //and thus me must honor it.
        return valueHash.orElse(Keccak256.ZERO_HASH);
    }

    @Override
    public synchronized Keccak256 getCodeHashStandard(RskAddress addr) {

        if (!isExist(addr)) {
            return Keccak256.ZERO_HASH;
        }

        if (!isContract(addr)) {
            return KECCAK_256_OF_EMPTY_ARRAY;
        }

        byte[] key = trieKeyMapper.getCodeKey(addr);

        return internalGetValueHash(key).orElse(KECCAK_256_OF_EMPTY_ARRAY);
    }

    @Override
    public synchronized byte[] getCode(RskAddress addr) {
        if (!isExist(addr)) {
            return EMPTY_BYTE_ARRAY;
        }

        AccountState account = getAccountState(addr);
        if (account.isHibernated()) {
            return EMPTY_BYTE_ARRAY;
        }

        byte[] key = trieKeyMapper.getCodeKey(addr);
        return internalGet(key);
    }

    @Override
    public boolean isContract(RskAddress addr) {
        byte[] prefix = trieKeyMapper.getAccountStoragePrefixKey(addr);
        return internalGet(prefix) != null;
    }

    @Override
    public synchronized void addStorageRow(RskAddress addr, DataWord key, DataWord value) {
        // DataWords are stored stripping leading zeros.
        addStorageBytes(addr, key, value.getByteArrayForStorage());
    }

    @Override
    public synchronized void addStorageBytes(RskAddress addr, DataWord key, byte[] value) {
        // This should not happen in production because contracts are created before storage cells are added to them.
        // But it happens in Repository tests, that create only storage row cells.
        if (!isExist(addr)) {
            createAccount(addr);
            setupContract(addr);
        }

        byte[] triekey = trieKeyMapper.getAccountStorageKey(addr, key);

        // Special case: if the value is an empty vector, we pass "null" which commands the trie to remove the item.
        // Note that if the call comes from addStorageRow(), this method will already have replaced 0 by null, so the
        // conversion here only applies if this is called directly. If suppose this only occurs in tests, but it can
        // also occur in precompiled contracts that store data directly using this method.
        if (value == null || value.length == 0) {
            internalPut(triekey, null);
        } else {
            internalPut(triekey, value);
        }
    }

    @Override
    public synchronized DataWord getStorageValue(RskAddress addr, DataWord key) {
        byte[] triekey = trieKeyMapper.getAccountStorageKey(addr, key);
        byte[] value = internalGet(triekey);
        if (value == null) {
            return null;
        }

        return DataWord.valueOf(value);
    }

    @Override
    public synchronized byte[] getStorageBytes(RskAddress addr, DataWord key) {
        byte[] triekey = trieKeyMapper.getAccountStorageKey(addr, key);
        return internalGet(triekey);
    }

    @Override
    public Iterator<DataWord> getStorageKeys(RskAddress addr) {
        // -1 b/c the first bit is implicit in the storage node
        return mutableTrie.getStorageKeys(addr);
    }

    @Override
    public int getStorageKeysCount(RskAddress addr) {
        // FIXME(diegoll): I think it's kind of insane to iterate the whole tree looking for storage keys for this address
        //  I think we can keep a counter for the keys, using the find function for detecting duplicates and so on
        int storageKeysCount = 0;
        Iterator<DataWord> keysIterator = getStorageKeys(addr);
        for(;keysIterator.hasNext(); keysIterator.next()) {
            storageKeysCount ++;
        }
        return storageKeysCount;
    }

    @Override
    public synchronized Coin getBalance(RskAddress addr) {
        AccountState account = getAccountState(addr);
        return (account == null) ? Coin.ZERO: account.getBalance();
    }

    @Override
    public synchronized Coin addBalance(RskAddress addr, Coin value) {
        AccountState account = getAccountStateOrCreateNew(addr);

        if (value.equals(Coin.ZERO)) {
            return account.getBalance();
        }

        Coin result = account.addToBalance(value);
        updateAccountState(addr, account);

        return result;
    }

    @Override
    public synchronized Set<RskAddress> getAccountsKeys() {
        Set<RskAddress> result = new HashSet<>();
        //TODO(diegoll): this is needed when trie is a MutableTrieCache, check if makes sense to commit here
        mutableTrie.commit();
        Trie trie = mutableTrie.getTrie();
        Iterator<IterationElement> preOrderIterator = trie.getPreOrderIterator(true);
        while (preOrderIterator.hasNext()) {
            TrieKeySlice nodeKey = preOrderIterator.next().getNodeKey();
            int nodeKeyLength = nodeKey.length();
            if (nodeKeyLength == (1 + TrieKeyMapper.SECURE_KEY_SIZE + RskAddress.LENGTH_IN_BYTES) * Byte.SIZE) {
                byte[] address = nodeKey.slice(nodeKeyLength - RskAddress.LENGTH_IN_BYTES * Byte.SIZE, nodeKeyLength).encode();
                result.add(new RskAddress(address));
            }
        }
        return result;
    }

    // To start tracking, a new repository is created, with a MutableTrieCache in the middle
    @Override
    public synchronized Repository startTracking() {
        return new MutableRepository(new MutableTrieCache(mutableTrie), new MutableTrieCache(transientTrie), tracker);
    }

    @Override
    public void save() {
        mutableTrie.save();
    }

    @Override
    public synchronized void commit() {
        mutableTrie.commit();
        transientTrie.commit();
    }

    @Override
    public synchronized void rollback() {
        mutableTrie.rollback();
        transientTrie.rollback();
    }

    @Override
    public synchronized byte[] getRoot() {
        mutableTrie.save();

        Keccak256 rootHash = mutableTrie.getHash();
        logger.trace("getting repository root hash {}", rootHash);
        return rootHash.getBytes();
    }

    @Override
    public synchronized void updateAccountState(RskAddress addr, final AccountState accountState) {
        byte[] accountKey = trieKeyMapper.getAccountKey(addr);
        internalPut(accountKey, accountState.getEncoded());
    }

    @VisibleForTesting
    public byte[] getStorageStateRoot(RskAddress addr) {
        byte[] prefix = trieKeyMapper.getAccountStoragePrefixKey(addr);

        // The value should be ONE_BYTE_ARRAY, but we don't need to check nothing else could be there.
        Trie storageRootNode = mutableTrie.getTrie().find(prefix);
        if (storageRootNode == null) {
            return HashUtil.EMPTY_TRIE_HASH;
        }

        // Now it's a bit tricky what to return: if I return the storageRootNode hash then it's counting the "0x01"
        // value, so the try one gets will never match the trie one gets if creating the trie without any other data.
        // Unless the PDV trie is used. The best we can do is to return storageRootNode hash
        return storageRootNode.getHash().getBytes();
    }

    @Nonnull
    private synchronized AccountState getAccountStateOrCreateNew(RskAddress addr) {
        AccountState account = getAccountState(addr);
        return (account == null) ? createAccount(addr) : account;
    }

    private byte[] getAccountData(RskAddress addr) {
        byte[] accountKey = trieKeyMapper.getAccountKey(addr);
        return internalGet(accountKey);
    }

    private void internalPut(byte[] key, byte[] value) {
        tracker.addNewWrittenKey(new ByteArrayWrapper(key));
        mutableTrie.put(key, value);
    }

    private byte[] internalGet(byte[] key) {
        tracker.addNewReadKey(new ByteArrayWrapper(key));
        return mutableTrie.get(key);
    }

    private Uint24 internalGetValueLength(byte[] key) {
        tracker.addNewReadKey(new ByteArrayWrapper(key));
        return mutableTrie.getValueLength(key);
    }

    private Optional<Keccak256> internalGetValueHash(byte[] key) {
        tracker.addNewReadKey(new ByteArrayWrapper(key));
        return mutableTrie.getValueHash(key);
    }

    @Override
    public void addTransientStorageRow(RskAddress addr, DataWord key, DataWord value) {
        addTransientStorageBytes(addr, key, value.getByteArrayForStorage());
    }

    @Override
    public void addTransientStorageBytes(RskAddress addr, DataWord key, byte[] value) {
        byte[] triekey = trieKeyMapper.getAccountStorageKey(addr, key);

        // Special case: if the value is an empty vector, we pass "null" which commands the trie to remove the item.
        // Note that if the call comes from addTransientStorageRow(), this method will already have replaced 0 by null, so the
        // conversion here only applies if this is called directly. If suppose this only occurs in tests, but it can
        // also occur in precompiled contracts that store data directly using this method.
        if (value == null || value.length == 0) {
            transientTrie.put(triekey, null);
        } else {
            transientTrie.put(triekey, value);
        }
    }

    @Override
    public void clearTransientStorage() {
        this.transientTrie = aInMemoryMutableTrie();
    }

    @Nullable
    @Override
    public DataWord getTransientStorageValue(RskAddress addr, DataWord key) {
        byte[] value = getTransientStorageBytes(addr, key);
        if (value == null) {
            return null;
        }

        return DataWord.valueOf(value);
    }

    @Nullable
    @Override
    public byte[] getTransientStorageBytes(RskAddress addr, DataWord key) {
        byte[] triekey = trieKeyMapper.getAccountStorageKey(addr, key);
        return transientTrie.get(triekey);
    }
}
