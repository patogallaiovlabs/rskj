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
import org.bouncycastle.util.encoders.Hex;
import org.ethereum.datasource.HashMapDB;
import org.ethereum.db.ByteArrayWrapper;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Random;
import java.util.concurrent.ForkJoinPool;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

class TrieParallelHasherTest {

    private static ForkJoinPool pool;

    @BeforeAll
    static void setUp() {
        pool = new ForkJoinPool(4);
    }

    @AfterAll
    static void tearDown() throws InterruptedException {
        pool.shutdown();
        pool.awaitTermination(10, TimeUnit.SECONDS);
    }

    /** Records the order of puts and fails if the store is read off the test thread. */
    private static final class RecordingDB extends HashMapDB {
        private final Thread owner = Thread.currentThread();
        private final List<String> puts = Collections.synchronizedList(new ArrayList<>());
        private volatile boolean offThreadAccess;

        @Override
        public byte[] get(byte[] key) {
            if (Thread.currentThread() != owner) {
                offThreadAccess = true;
            }
            return super.get(key);
        }

        @Override
        public byte[] put(byte[] key, byte[] value) {
            if (Thread.currentThread() != owner) {
                offThreadAccess = true;
            }
            puts.add(Hex.toHexString(key) + ":" + Hex.toHexString(value));
            return super.put(key, value);
        }
    }

    @Test
    void parallelPrehashThenSaveIsIdenticalToSequentialSave() {
        // base state, saved sequentially
        RecordingDB baseDb = new RecordingDB();
        TrieStoreImpl baseStore = new TrieStoreImpl(baseDb, false);
        Trie base = new Trie(baseStore);
        Random random = new Random(42);
        List<byte[]> keys = new ArrayList<>();
        for (int i = 0; i < 4000; i++) {
            byte[] key = randomBytes(random, 1 + random.nextInt(40));
            keys.add(key);
            base = base.put(key, randomValue(random));
        }
        baseStore.save(base);
        Keccak256 baseHash = base.getHash();

        RecordingDB dbA = copyOf(baseDb);
        RecordingDB dbB = copyOf(baseDb);
        TrieStoreImpl storeA = new TrieStoreImpl(dbA, false);
        TrieStoreImpl storeB = new TrieStoreImpl(dbB, false);

        // fresh roots loaded from the store, as during block import
        Trie trieA = storeA.retrieve(baseHash.getBytes()).orElseThrow(IllegalStateException::new);
        Trie trieB = storeB.retrieve(baseHash.getBytes()).orElseThrow(IllegalStateException::new);

        Random ops = new Random(7);
        for (int i = 0; i < 3000; i++) {
            int op = ops.nextInt(10);
            byte[] key;
            byte[] value = null;
            if (op < 4) {
                key = randomBytes(ops, 1 + ops.nextInt(40));
                value = randomValue(ops);
            } else if (op < 8) {
                key = keys.get(ops.nextInt(keys.size()));
                value = randomValue(ops);
            } else {
                key = keys.get(ops.nextInt(keys.size()));
            }
            if (value == null) {
                trieA = trieA.delete(key);
                trieB = trieB.delete(key);
            } else {
                trieA = trieA.put(key, value);
                trieB = trieB.put(key, value);
            }
        }

        dbA.puts.clear();
        dbB.puts.clear();

        storeA.save(trieA);

        assertTrue(TrieParallelHasher.prehash(trieB, pool, 4));
        assertFalse(dbB.offThreadAccess, "workers must not touch the store");
        assertTrue(dbB.puts.isEmpty(), "prehash must not write");
        storeB.save(trieB);

        assertEquals(trieA.getHash(), trieB.getHash());
        assertEquals(dbA.puts, dbB.puts);
        assertEquals(contents(dbA), contents(dbB));
        assertFalse(dbB.offThreadAccess);
    }

    @Test
    void parallelHashOfFreshTrieMatchesSequential() {
        Random random = new Random(1);
        Trie a = new Trie(new TrieStoreImpl(new HashMapDB(), false));
        Trie b = new Trie(new TrieStoreImpl(new HashMapDB(), false));
        for (int i = 0; i < 2000; i++) {
            byte[] key = randomBytes(random, 32);
            byte[] value = randomValue(random);
            a = a.put(key, value);
            b = b.put(key, value);
        }

        assertTrue(TrieParallelHasher.prehash(b, pool, 4));
        assertEquals(a.getHash(), b.getHash());
        assertArrayEquals(a.toMessage(), b.toMessage());
    }

    @Test
    void smallTrieIsLeftToTheSequentialPath() {
        Trie trie = new Trie(new TrieStoreImpl(new HashMapDB(), false));
        for (int i = 0; i < 10; i++) {
            trie = trie.put(new byte[]{(byte) i, 1}, new byte[]{(byte) i});
        }

        assertFalse(TrieParallelHasher.prehash(trie, pool, 4));
        assertFalse(trie.hasCachedHash());
    }

    @Test
    void savedOrHashedRootIsSkipped() {
        Random random = new Random(3);
        Trie trie = new Trie(new TrieStoreImpl(new HashMapDB(), false));
        for (int i = 0; i < 1000; i++) {
            trie = trie.put(randomBytes(random, 32), randomValue(random));
        }
        trie.getHash();

        assertFalse(TrieParallelHasher.prehash(trie, pool, 4));
    }

    private static RecordingDB copyOf(HashMapDB source) {
        RecordingDB copy = new RecordingDB();
        for (ByteArrayWrapper key : source.keys()) {
            copy.put(key.getData(), source.get(key.getData()));
        }
        return copy;
    }

    private static List<String> contents(HashMapDB db) {
        List<String> rows = new ArrayList<>();
        for (ByteArrayWrapper key : db.keys()) {
            rows.add(Hex.toHexString(key.getData()) + ":" + Hex.toHexString(db.get(key.getData())));
        }
        Collections.sort(rows);
        return rows;
    }

    private static byte[] randomValue(Random random) {
        // mix of embedded-size, short and long (> 32 bytes, stored separately) values
        int kind = random.nextInt(3);
        int length = kind == 0 ? 1 + random.nextInt(4) : kind == 1 ? 1 + random.nextInt(32) : 33 + random.nextInt(100);
        return randomBytes(random, length);
    }

    private static byte[] randomBytes(Random random, int length) {
        byte[] bytes = new byte[length];
        random.nextBytes(bytes);
        return bytes;
    }
}
