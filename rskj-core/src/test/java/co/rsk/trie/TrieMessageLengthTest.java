/*
 * This file is part of RskJ
 * Copyright (C) 2026 RSK Labs Ltd.
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
package co.rsk.trie;

import co.rsk.crypto.Keccak256;
import org.ethereum.datasource.HashMapDB;
import org.ethereum.db.ByteArrayWrapper;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * getMessageLength() is computed arithmetically (without serializing/hashing) for nodes that were not
 * encoded yet. It must always equal the real encoding length, and the encodings/hashes must not change.
 */
class TrieMessageLengthTest {

    @Test
    void arithmeticLengthMatchesEncodingOnRandomTries() {
        for (int seed = 0; seed < 40; seed++) {
            checkRandomTrie(seed);
        }
    }

    @Test
    void singleNodeShapes() {
        // empty root, short value, embeddable leaf boundary, long value
        checkTrie(new Trie());
        checkTrie(new Trie().put(new byte[]{1}, new byte[]{2}));
        for (int len = 1; len <= 70; len++) {
            Trie trie = new Trie()
                    .put(new byte[]{0x00, 0x01}, new byte[len])
                    .put(new byte[]{(byte) 0x80, 0x01}, new byte[]{7})
                    .put(new byte[]{0x00, (byte) 0x81}, new byte[len + 1]);
            checkTrie(trie);
        }
    }

    private static void checkRandomTrie(long seed) {
        Random random = new Random(seed);
        List<byte[]> keys = new ArrayList<>();
        List<byte[]> values = new ArrayList<>();
        Trie trie = new Trie();
        int n = 1 + random.nextInt(300);
        for (int i = 0; i < n; i++) {
            byte[] key = randomKey(random, keys);
            byte[] value = randomValue(random);
            keys.add(key);
            values.add(value);
            trie = trie.put(key, value);
            if (random.nextInt(10) == 0) {
                // check intermediate tries too, before anything below is hashed
                checkLengthsBeforeEncoding(trie);
            }
        }

        Map<ByteArrayWrapper, byte[]> finalState = new HashMap<>();
        for (int i = 0; i < keys.size(); i++) {
            finalState.put(new ByteArrayWrapper(keys.get(i)), values.get(i));
        }

        // deletes (and re-merges of shared paths)
        for (int i = 0; i < n / 4; i++) {
            byte[] key = keys.get(random.nextInt(keys.size()));
            trie = trie.delete(key);
            finalState.remove(new ByteArrayWrapper(key));
        }

        checkTrie(trie);

        // root hash does not depend on insertion order
        List<Map.Entry<ByteArrayWrapper, byte[]>> entries = new ArrayList<>(finalState.entrySet());
        Collections.shuffle(entries, random);
        Trie other = new Trie();
        for (Map.Entry<ByteArrayWrapper, byte[]> e : entries) {
            other = other.put(e.getKey().getData(), e.getValue());
        }
        assertEquals(trie.getHash(), other.getHash());

        // store roundtrip: retrieved nodes re-encode to the same bytes and lengths
        TrieStoreImpl store = new TrieStoreImpl(new HashMapDB());
        store.save(trie);
        Keccak256 rootHash = trie.getHash();
        Trie retrieved = store.retrieve(rootHash.getBytes()).orElseThrow(IllegalStateException::new);
        assertEquals(rootHash, retrieved.getHash());
        checkTrie(retrieved);
    }

    private static byte[] randomKey(Random random, List<byte[]> existing) {
        if (!existing.isEmpty() && random.nextInt(8) == 0) {
            // overwrite an existing key
            return existing.get(random.nextInt(existing.size()));
        }
        byte[] key;
        if (!existing.isEmpty() && random.nextInt(3) == 0) {
            // share a prefix with an existing key, forcing splits deep in the trie
            byte[] base = existing.get(random.nextInt(existing.size()));
            key = new byte[base.length + random.nextInt(3)];
            System.arraycopy(base, 0, key, 0, base.length);
            int flip = random.nextInt(key.length);
            key[flip] ^= (byte) (1 << random.nextInt(8));
        } else {
            key = new byte[1 + random.nextInt(40)];
            random.nextBytes(key);
        }
        return key;
    }

    private static byte[] randomValue(Random random) {
        int kind = random.nextInt(4);
        int len;
        if (kind == 0) {
            len = 1 + random.nextInt(8);       // tiny, embeddable leaves
        } else if (kind == 1) {
            len = 1 + random.nextInt(32);      // short values
        } else if (kind == 2) {
            len = 33 + random.nextInt(100);    // long values (stored by hash)
        } else {
            len = 28 + random.nextInt(12);     // around the 44-byte embedding boundary
        }
        byte[] value = new byte[len];
        random.nextBytes(value);
        return value;
    }

    /** Lengths computed arithmetically on a trie that was never encoded must match the later encodings. */
    private static void checkLengthsBeforeEncoding(Trie root) {
        Map<Trie, Integer> lengths = new IdentityHashMap<>();
        collectLengths(root, lengths);
        for (Map.Entry<Trie, Integer> e : lengths.entrySet()) {
            assertEquals(e.getKey().toMessage().length, (int) e.getValue());
        }
    }

    private static void checkTrie(Trie root) {
        checkLengthsBeforeEncoding(root);
        root.getHash();
        checkEncodedNodes(root);
    }

    private static void collectLengths(Trie node, Map<Trie, Integer> lengths) {
        lengths.put(node, node.getMessageLength());
        node.getLeft().getNode().ifPresent(child -> collectLengths(child, lengths));
        node.getRight().getNode().ifPresent(child -> collectLengths(child, lengths));
    }

    private static void checkEncodedNodes(Trie node) {
        byte[] message = node.toMessage();
        assertEquals(message.length, node.getMessageLength());
        // a fresh node decoded from the encoding (no cached encoding) computes the same length
        // and re-encodes to the same bytes
        Trie decoded = Trie.fromMessage(message, null);
        assertEquals(message.length, decoded.getMessageLength());
        assertArrayEquals(message, decoded.toMessage());
        assertEquals(node.getHash(), decoded.getHash());
        assertTrue(message.length > 0);
        node.getLeft().getNode().ifPresent(TrieMessageLengthTest::checkEncodedNodes);
        node.getRight().getNode().ifPresent(TrieMessageLengthTest::checkEncodedNodes);
    }
}
