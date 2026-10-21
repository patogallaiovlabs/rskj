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

import co.rsk.db.MutableTrieCache;
import co.rsk.db.MutableTrieImpl;
import org.ethereum.datasource.HashMapDB;
import org.ethereum.db.ByteArrayWrapper;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

import java.util.*;

/**
 * Pins that {@link Trie#putAll(List, List)} (and the batched {@link MutableTrieCache#commit()})
 * produce exactly the same nodes as one {@link Trie#put(byte[], byte[])} per key.
 */
class TriePutAllTest {

    @Test
    void emptyBatchReturnsSameTrie() {
        Trie trie = new Trie().put(new byte[]{1}, new byte[]{1});
        Assertions.assertSame(trie, trie.putAll(Collections.emptyList(), Collections.emptyList()));
    }

    @Test
    void rejectsDeletes() {
        Trie trie = new Trie();
        List<byte[]> keys = Collections.singletonList(new byte[]{1});
        Assertions.assertThrows(IllegalArgumentException.class, () -> trie.putAll(keys, Collections.singletonList(null)));
        Assertions.assertThrows(IllegalArgumentException.class, () -> trie.putAll(keys, Collections.singletonList(new byte[0])));
    }

    @Test
    void unchangedValuesKeepSameRoot() {
        Random random = new Random(7);
        Map<ByteArrayWrapper, byte[]> initial = randomEntries(random, 200);
        Trie trie = putSequential(new Trie(), initial, random);

        List<byte[]> keys = new ArrayList<>();
        List<byte[]> values = new ArrayList<>();
        for (Map.Entry<ByteArrayWrapper, byte[]> e : initial.entrySet()) {
            keys.add(e.getKey().getData());
            values.add(e.getValue().clone());
        }

        Assertions.assertSame(trie, trie.putAll(keys, values));
    }

    @Test
    void duplicatedKeysLastWins() {
        Trie trie = new Trie();
        List<byte[]> keys = Arrays.asList(new byte[]{1, 2}, new byte[]{1}, new byte[]{1, 2});
        List<byte[]> values = Arrays.asList(new byte[]{9}, new byte[]{8}, new byte[]{7});
        Trie batched = trie.putAll(keys, values);
        Trie sequential = trie.put(new byte[]{1, 2}, new byte[]{9}).put(new byte[]{1}, new byte[]{8}).put(new byte[]{1, 2}, new byte[]{7});
        assertSameTrie(sequential, batched);
    }

    @Test
    void randomBatchesOnFreshTrie() {
        for (int seed = 0; seed < 300; seed++) {
            Random random = new Random(seed);
            Map<ByteArrayWrapper, byte[]> initial = randomEntries(random, random.nextInt(60));
            Trie base = putSequential(new Trie(), initial, random);

            Map<ByteArrayWrapper, byte[]> updates = randomUpdates(random, initial, 1 + random.nextInt(80));
            Trie sequential = putSequential(base, updates, random);
            Trie batched = putBatch(base, updates, random);

            assertSameTrie(sequential, batched);
        }
    }

    @Test
    void randomBatchesOnStoreBackedTrie() {
        for (int seed = 0; seed < 200; seed++) {
            Random random = new Random(1000 + seed);
            TrieStore store = new TrieStoreImpl(new HashMapDB());
            Map<ByteArrayWrapper, byte[]> initial = randomEntries(random, 1 + random.nextInt(150));
            Trie built = putSequential(new Trie(store), initial, random);
            store.save(built);

            Map<ByteArrayWrapper, byte[]> updates = randomUpdates(random, initial, 1 + random.nextInt(100));

            // fresh retrievals: children are hash-only references and long values are lazy
            Trie sequential = putSequential(store.retrieve(built.getHash().getBytes()).get(), updates, random);
            Trie batched = putBatch(store.retrieve(built.getHash().getBytes()).get(), updates, random);

            assertSameTrie(sequential, batched);

            // and the batched result persists and reloads to the same nodes
            store.save(batched);
            assertSameTrie(sequential, store.retrieve(batched.getHash().getBytes()).get());
        }
    }

    @Test
    void cacheCommitWithMixedDeletesMatchesSequentialPuts() {
        for (int seed = 0; seed < 200; seed++) {
            Random random = new Random(5000 + seed);
            TrieStore store = new TrieStoreImpl(new HashMapDB());
            Map<ByteArrayWrapper, byte[]> initial = randomEntries(random, 1 + random.nextInt(150));
            Trie built = putSequential(new Trie(store), initial, random);
            store.save(built);

            Map<ByteArrayWrapper, byte[]> ops = randomUpdates(random, initial, 1 + random.nextInt(100));
            List<ByteArrayWrapper> existing = new ArrayList<>(initial.keySet());
            int deletes = random.nextInt(30);
            for (int i = 0; i < deletes; i++) {
                ByteArrayWrapper key = random.nextBoolean() && !existing.isEmpty()
                        ? existing.get(random.nextInt(existing.size()))
                        : randomKey(random);
                ops.put(key, random.nextInt(4) == 0 ? new byte[0] : null);
            }

            // reference: one plain Trie.put per key, in random order
            Trie reference = putSequential(store.retrieve(built.getHash().getBytes()).get(), ops, random);

            // batched commit into a MutableTrieImpl
            MutableTrieImpl impl = new MutableTrieImpl(store, store.retrieve(built.getHash().getBytes()).get());
            MutableTrieCache cache = new MutableTrieCache(impl);
            applyTo(cache, ops);
            cache.commit();
            assertSameTrie(reference, impl.getTrie());

            // nested cache keeps the per-key path, then batches when reaching the MutableTrieImpl
            MutableTrieImpl impl2 = new MutableTrieImpl(store, store.retrieve(built.getHash().getBytes()).get());
            MutableTrieCache outer = new MutableTrieCache(impl2);
            MutableTrieCache inner = new MutableTrieCache(outer);
            applyTo(inner, ops);
            inner.commit();
            outer.commit();
            assertSameTrie(reference, impl2.getTrie());
        }
    }

    private static void applyTo(MutableTrieCache cache, Map<ByteArrayWrapper, byte[]> ops) {
        for (Map.Entry<ByteArrayWrapper, byte[]> e : ops.entrySet()) {
            cache.put(e.getKey().getData(), e.getValue());
        }
    }

    private static Trie putSequential(Trie trie, Map<ByteArrayWrapper, byte[]> entries, Random random) {
        List<Map.Entry<ByteArrayWrapper, byte[]>> list = new ArrayList<>(entries.entrySet());
        Collections.shuffle(list, random);
        for (Map.Entry<ByteArrayWrapper, byte[]> e : list) {
            trie = trie.put(e.getKey().getData(), e.getValue());
        }
        return trie;
    }

    private static Trie putBatch(Trie trie, Map<ByteArrayWrapper, byte[]> entries, Random random) {
        List<Map.Entry<ByteArrayWrapper, byte[]>> list = new ArrayList<>(entries.entrySet());
        Collections.shuffle(list, random);
        List<byte[]> keys = new ArrayList<>();
        List<byte[]> values = new ArrayList<>();
        for (Map.Entry<ByteArrayWrapper, byte[]> e : list) {
            keys.add(e.getKey().getData());
            values.add(e.getValue());
        }
        return trie.putAll(keys, values);
    }

    // keys from a tiny alphabet so that prefixes, shared paths and splits inside them are frequent
    private static ByteArrayWrapper randomKey(Random random) {
        int length = 1 + random.nextInt(5);
        byte[] key = new byte[length];
        for (int i = 0; i < length; i++) {
            key[i] = (byte) (random.nextInt(4) * 0x41 + (random.nextInt(8) == 0 ? 0x80 : 0));
        }
        return new ByteArrayWrapper(key);
    }

    private static byte[] randomValue(Random random) {
        int kind = random.nextInt(5);
        int length;
        if (kind == 0) {
            length = 33 + random.nextInt(60); // long value, stored apart
        } else if (kind == 1) {
            length = 20 + random.nextInt(13); // short but not embeddable when combined with path
        } else {
            length = 1 + random.nextInt(4); // small, embeddable leaves
        }
        byte[] value = new byte[length];
        random.nextBytes(value);
        value[0] |= 1; // non-empty, non-trivial
        return value;
    }

    private static Map<ByteArrayWrapper, byte[]> randomEntries(Random random, int count) {
        Map<ByteArrayWrapper, byte[]> entries = new LinkedHashMap<>();
        for (int i = 0; i < count; i++) {
            entries.put(randomKey(random), randomValue(random));
        }
        return entries;
    }

    private static Map<ByteArrayWrapper, byte[]> randomUpdates(Random random, Map<ByteArrayWrapper, byte[]> initial, int count) {
        Map<ByteArrayWrapper, byte[]> updates = new LinkedHashMap<>();
        List<ByteArrayWrapper> existing = new ArrayList<>(initial.keySet());
        for (int i = 0; i < count; i++) {
            int kind = random.nextInt(3);
            if (kind == 0 && !existing.isEmpty()) {
                ByteArrayWrapper key = existing.get(random.nextInt(existing.size()));
                // sometimes rewrite the same value, which must not change the node
                updates.put(key, random.nextBoolean() ? initial.get(key).clone() : randomValue(random));
            } else {
                updates.put(randomKey(random), randomValue(random));
            }
        }
        return updates;
    }

    private static void assertSameTrie(Trie expected, Trie actual) {
        Assertions.assertEquals(expected.getHash(), actual.getHash());
        Assertions.assertArrayEquals(expected.toMessage(), actual.toMessage());
        List<byte[]> expectedNodes = nodeMessages(expected);
        List<byte[]> actualNodes = nodeMessages(actual);
        Assertions.assertEquals(expectedNodes.size(), actualNodes.size());
        for (int i = 0; i < expectedNodes.size(); i++) {
            Assertions.assertArrayEquals(expectedNodes.get(i), actualNodes.get(i));
        }
    }

    private static List<byte[]> nodeMessages(Trie trie) {
        List<byte[]> messages = new ArrayList<>();
        if (trie.isEmptyTrie()) {
            return messages;
        }
        Iterator<IterationElement> iterator = trie.getPreOrderIterator();
        while (iterator.hasNext()) {
            Trie node = iterator.next().getNode();
            messages.add(node.toMessage());
            messages.add(node.getChildrenSize().encode());
        }
        return messages;
    }
}
