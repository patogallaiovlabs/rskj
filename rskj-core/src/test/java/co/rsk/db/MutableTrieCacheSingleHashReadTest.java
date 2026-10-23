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

package co.rsk.db;

import co.rsk.core.RskAddress;
import co.rsk.core.types.ints.Uint24;
import co.rsk.crypto.Keccak256;
import co.rsk.trie.MutableTrie;
import co.rsk.trie.Trie;
import org.ethereum.crypto.Keccak256Helper;
import org.ethereum.db.ByteArrayWrapper;
import org.ethereum.db.TrieKeyMapper;
import org.ethereum.vm.DataWord;
import org.junit.jupiter.api.Test;

import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Pins read semantics of nested MutableTrieCache levels now that the key wrapper and account
 * wrapper are computed once and passed down, and that each level probes its map once.
 */
class MutableTrieCacheSingleHashReadTest {

    private final TrieKeyMapper mapper = new TrieKeyMapper();
    private final RskAddress addr = new RskAddress("0x0000000000000000000000000000000000001234");
    private final RskAddress other = new RskAddress("0x0000000000000000000000000000000000005678");

    private byte[] accountKey(RskAddress a) {
        return mapper.getAccountKey(a);
    }

    private byte[] storageKey(RskAddress a, int k) {
        return mapper.getAccountStorageKey(a, DataWord.valueOf(k));
    }

    private static void assertReads(MutableTrie trie, byte[] key, byte[] expected) {
        assertArrayEquals(expected, trie.get(key));
        assertArrayEquals(expected, trie.get(new ByteArrayWrapper(key.clone())));
        Uint24 expectedLength = expected == null ? Uint24.ZERO : new Uint24(expected.length);
        assertEquals(expectedLength, trie.getValueLength(key));
        assertEquals(expectedLength, trie.getValueLength(new ByteArrayWrapper(key.clone())));
        Optional<Keccak256> expectedHash = expected == null
                ? Optional.empty()
                : Optional.of(new Keccak256(Keccak256Helper.keccak256(expected)));
        assertEquals(expectedHash, trie.getValueHash(key));
        assertEquals(expectedHash, trie.getValueHash(new ByteArrayWrapper(key.clone())));
    }

    @Test
    void nestedLevelsResolveCachedDeletedAndParentValues() {
        MutableTrieImpl base = new MutableTrieImpl(null, new Trie());
        byte[] s1 = storageKey(addr, 1);
        byte[] s2 = storageKey(addr, 2);
        byte[] s3 = storageKey(addr, 3);
        base.put(accountKey(addr), new byte[]{1});
        base.put(s1, new byte[]{10});
        base.put(s2, new byte[]{20});
        base.put(s3, new byte[]{30});

        MutableTrieCache mid = new MutableTrieCache(base);
        mid.put(s1, new byte[]{11});
        mid.put(s2, null);

        MutableTrieCache top = new MutableTrieCache(mid);
        top.put(s3, new byte[]{33});

        assertReads(top, s1, new byte[]{11});       // cached in middle level
        assertReads(top, s2, null);                 // deleted marker in middle level
        assertReads(top, s3, new byte[]{33});       // cached in top level
        assertReads(top, accountKey(addr), new byte[]{1}); // only in base
        assertReads(top, storageKey(addr, 4), null); // absent everywhere
        assertReads(top, accountKey(other), null);
    }

    @Test
    void deletedAccountHidesParentButNotLaterOverrides() {
        MutableTrieImpl base = new MutableTrieImpl(null, new Trie());
        byte[] s1 = storageKey(addr, 1);
        byte[] s2 = storageKey(addr, 2);
        byte[] os = storageKey(other, 1);
        base.put(accountKey(addr), new byte[]{1});
        base.put(s1, new byte[]{10});
        base.put(s2, new byte[]{20});
        base.put(os, new byte[]{40});

        MutableTrieCache mid = new MutableTrieCache(base);
        mid.deleteRecursive(accountKey(addr));
        mid.put(s2, new byte[]{22});

        MutableTrieCache top = new MutableTrieCache(mid);

        assertReads(top, s1, null);                 // hidden by recursive delete in mid
        assertReads(top, accountKey(addr), null);
        assertReads(top, s2, new byte[]{22});       // re-put after delete wins
        assertReads(top, os, new byte[]{40});       // other account unaffected

        top.put(s1, new byte[]{12});
        assertReads(top, s1, new byte[]{12});
        assertReads(mid, s1, null);
    }

    @Test
    void readCacheIsNotCorruptedByMutatingCallerKey() {
        MutableTrieImpl base = new MutableTrieImpl(null, new Trie());
        byte[] s1 = storageKey(addr, 1);
        byte[] s2 = storageKey(addr, 2);
        base.put(s1, new byte[]{10});
        base.put(s2, new byte[]{20});

        byte[] key = s1.clone();
        assertArrayEquals(new byte[]{10}, base.get(new ByteArrayWrapper(key)));
        // mutate the caller's array into s2: the cached entry for s1 must stay keyed by s1's bytes
        System.arraycopy(s2, 0, key, 0, key.length);
        assertArrayEquals(new byte[]{20}, base.get(key));
        assertArrayEquals(new byte[]{10}, base.get(s1));
        assertArrayEquals(new byte[]{20}, base.get(s2));
    }

    @Test
    void wrapperCopyIsEqualIndependentAndKeepsHash() {
        byte[] data = new byte[]{1, 2, 3};
        ByteArrayWrapper original = new ByteArrayWrapper(data);
        ByteArrayWrapper copy = original.copy();
        assertEquals(original, copy);
        assertEquals(original.hashCode(), copy.hashCode());
        assertNotSame(original.getData(), copy.getData());
        data[0] = 9;
        assertArrayEquals(new byte[]{1, 2, 3}, copy.getData());
        assertEquals(new ByteArrayWrapper(new byte[]{1, 2, 3}).hashCode(), copy.hashCode());
    }
}
