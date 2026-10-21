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

package co.rsk.db;

import co.rsk.core.RskAddress;
import co.rsk.core.types.ints.Uint24;
import co.rsk.crypto.Keccak256;
import co.rsk.trie.*;
import org.ethereum.db.ByteArrayWrapper;
import org.ethereum.db.TrieKeyMapper;
import org.ethereum.vm.DataWord;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

public class MutableTrieImpl implements MutableTrie {

    // marks a cached miss (trie.find returned null)
    private static final Trie NULL_NODE = new Trie();
    private static final int READ_CACHE_MAX_ENTRIES = 200_000;

    /**
     * Memoized trie.find results for one immutable root. Both keys (cloned) and Trie nodes are
     * immutable, so concurrent readers can share it; a race only costs a duplicate find.
     */
    private static final class ReadCache {
        private final Trie root;
        private final Map<ByteArrayWrapper, Trie> nodes = new ConcurrentHashMap<>();

        private ReadCache(Trie root) {
            this.root = root;
        }
    }

    private volatile ReadCache readCache;

    private Trie trie;
    private TrieKeyMapper trieKeyMapper = new TrieKeyMapper();
    private TrieStore trieStore;

    public MutableTrieImpl(TrieStore trieStore, Trie trie) {
        this.trieStore = trieStore;
        this.trie = trie;
    }

    @Override
    public Trie getTrie() {
        return trie;
    }

    @Override
    public Keccak256 getHash() {
        return trie.getHash();
    }

    @Override
    public byte[] get(byte[] key) {
        Trie node = findCached(key);
        return node == null ? null : node.getValue();
    }

    @Override
    public void put(byte[] key, byte[] value) {
        trie = trie.put(key, value);
        readCache = null;
    }

    @Override
    public void put(ByteArrayWrapper key, byte[] value) {
        trie = trie.put(key, value);
        readCache = null;
    }

    @Override
    public void put(String key, byte[] value) {
        trie = trie.put(key, value);
        readCache = null;
    }

    /**
     * Returns exactly what {@code trie.find(key)} returns for the current root, memoized per root.
     * The cache is bound to the root object it was filled from, so a cache built for an older
     * root is never consulted once {@code trie} has been reassigned (even under a racy read).
     */
    private Trie findCached(byte[] key) {
        ReadCache cache = readCache;
        Trie root = trie;
        if (cache == null || cache.root != root) {
            cache = new ReadCache(root);
            readCache = cache;
        }

        ByteArrayWrapper wrappedKey = new ByteArrayWrapper(key.clone());
        Trie node = cache.nodes.get(wrappedKey);
        if (node != null) {
            return node == NULL_NODE ? null : node;
        }

        node = root.find(key);
        if (cache.nodes.size() >= READ_CACHE_MAX_ENTRIES) {
            cache.nodes.clear();
        }
        cache.nodes.put(wrappedKey, node == null ? NULL_NODE : node);
        return node;
    }

    /**
     * Inserts or updates several keys at once, see {@link Trie#putAll(List, List)}.
     * Values must be non-null and non-empty.
     */
    public void putAll(List<byte[]> keys, List<byte[]> values) {
        trie = trie.putAll(keys, values);
    }

    @Override
    public Uint24 getValueLength(byte[] key) {
        Trie atrie = findCached(key);
        if (atrie == null) {
            // TODO(mc) should be null?
            return Uint24.ZERO;
        }

        return atrie.getValueLength();
    }

    @Override
    public Optional<Keccak256> getValueHash(byte[] key) {
        Trie atrie = findCached(key);
        if (atrie == null) {
            return Optional.empty();
        }
        return Optional.of(atrie.getValueHash());
    }

    @Override
    public Iterator<DataWord> getStorageKeys(RskAddress addr) {
        byte[] accountStorageKey = trieKeyMapper.getAccountStoragePrefixKey(addr);
        final int storageKeyOffset = (TrieKeyMapper.storagePrefix().length + TrieKeyMapper.SECURE_KEY_SIZE) * Byte.SIZE - 1;
        Trie storageTrie = trie.find(accountStorageKey);

        if (storageTrie != null) {
            Iterator<IterationElement> storageIterator = storageTrie.getPreOrderIterator();
            storageIterator.next(); // skip storage root
            return new StorageKeysIterator(storageIterator, storageKeyOffset);
        }
        return Collections.emptyIterator();
    }

    @Override
    public void deleteRecursive(byte[] key) {
        trie = trie.deleteRecursive(key);
        readCache = null;
    }

    @Override
    public void save() {
        if (trieStore != null) {
            trieStore.save(trie);
        }
    }

    @Override
    public void commit() {
        // TODO(mc) is it OK to leave this empty? why?
    }

    @Override
    public void rollback() {
        // TODO(mc) is it OK to leave this empty? why?
    }

    @Override
    public Set<ByteArrayWrapper> collectKeys(int size) {
        return trie.collectKeys(size);
    }

    private static class StorageKeysIterator implements Iterator<DataWord> {
        private final Iterator<IterationElement> storageIterator;
        private final int storageKeyOffset;
        private DataWord currentStorageKey;

        StorageKeysIterator(Iterator<IterationElement> storageIterator, int storageKeyOffset) {
            this.storageIterator = storageIterator;
            this.storageKeyOffset = storageKeyOffset;
        }

        @Override
        public boolean hasNext() {
            if (currentStorageKey != null) {
                return true;
            }
            while (storageIterator.hasNext()) {
                IterationElement iterationElement = storageIterator.next();
                if (iterationElement.getNode().getValue() != null) {
                    TrieKeySlice nodeKey = iterationElement.getNodeKey();
                    byte[] storageExpandedKeySuffix = nodeKey.slice(storageKeyOffset, nodeKey.length()).encode();
                    currentStorageKey = DataWord.valueOf(storageExpandedKeySuffix);
                    return true;
                }
            }
            return false;
        }

        @Override
        public DataWord next() {
            if (currentStorageKey == null && !hasNext()) {
                throw new NoSuchElementException();
            }

            DataWord next = currentStorageKey;
            currentStorageKey = null;
            return next;
        }
    }
}
