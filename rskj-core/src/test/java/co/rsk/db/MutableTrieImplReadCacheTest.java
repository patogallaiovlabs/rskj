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

import co.rsk.core.types.ints.Uint24;
import co.rsk.crypto.Keccak256;
import co.rsk.trie.Trie;
import org.ethereum.crypto.Keccak256Helper;
import org.ethereum.db.ByteArrayWrapper;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;

class MutableTrieImplReadCacheTest {

    private static byte[] b(String s) {
        return s.getBytes(StandardCharsets.UTF_8);
    }

    @Test
    void getAfterPutReturnsFreshValue() {
        MutableTrieImpl mt = new MutableTrieImpl(null, new Trie());
        mt.put(b("k"), b("v1"));
        assertArrayEquals(b("v1"), mt.get(b("k")));
        assertEquals(new Uint24(2), mt.getValueLength(b("k")));

        mt.put(b("k"), b("value2"));
        assertArrayEquals(b("value2"), mt.get(b("k")));
        assertEquals(new Uint24(6), mt.getValueLength(b("k")));
        assertEquals(Optional.of(new Keccak256(Keccak256Helper.keccak256(b("value2")))), mt.getValueHash(b("k")));

        mt.put(new ByteArrayWrapper(b("k")), b("v3"));
        assertArrayEquals(b("v3"), mt.get(b("k")));

        mt.put("k", b("v4"));
        assertArrayEquals(b("v4"), mt.get(b("k")));
    }

    @Test
    void cachedMissStaysMissAndIsInvalidatedByPut() {
        MutableTrieImpl mt = new MutableTrieImpl(null, new Trie());
        mt.put(b("a"), b("x"));
        assertNull(mt.get(b("missing")));
        assertNull(mt.get(b("missing")));
        assertEquals(Uint24.ZERO, mt.getValueLength(b("missing")));
        assertEquals(Optional.empty(), mt.getValueHash(b("missing")));

        mt.put(b("missing"), b("now"));
        assertArrayEquals(b("now"), mt.get(b("missing")));
    }

    @Test
    void getAfterDeleteRecursiveReturnsFreshData() {
        MutableTrieImpl mt = new MutableTrieImpl(null, new Trie());
        mt.put(b("abc"), b("1"));
        mt.put(b("abcd"), b("2"));
        assertArrayEquals(b("1"), mt.get(b("abc")));
        assertArrayEquals(b("2"), mt.get(b("abcd")));

        mt.deleteRecursive(b("abc"));
        assertNull(mt.get(b("abc")));
        assertNull(mt.get(b("abcd")));
        assertEquals(Uint24.ZERO, mt.getValueLength(b("abcd")));
        assertEquals(Optional.empty(), mt.getValueHash(b("abcd")));
    }

    @Test
    void returnedArraysAreIndependentCopies() {
        MutableTrieImpl mt = new MutableTrieImpl(null, new Trie());
        mt.put(b("k"), b("value"));
        byte[] first = mt.get(b("k"));
        first[0] = 'X';
        assertArrayEquals(b("value"), mt.get(b("k")));
        assertNotSame(mt.get(b("k")), mt.get(b("k")));
    }

    @Test
    void mutatingLookupKeyDoesNotPoisonCache() {
        MutableTrieImpl mt = new MutableTrieImpl(null, new Trie());
        mt.put(b("aa"), b("1"));
        byte[] key = b("aa");
        assertArrayEquals(b("1"), mt.get(key));
        key[1] = 'b';
        assertNull(mt.get(key));
        assertArrayEquals(b("1"), mt.get(b("aa")));
    }

    @Test
    void matchesUncachedTrieAcrossManyKeys() {
        MutableTrieImpl mt = new MutableTrieImpl(null, new Trie());
        for (int i = 0; i < 500; i += 2) {
            mt.put(b(String.format("key%04d", i)), b("val" + i));
        }
        Trie reference = mt.getTrie();
        for (int round = 0; round < 2; round++) {
            for (int i = 0; i < 500; i++) {
                byte[] k = b(String.format("key%04d", i));
                assertArrayEquals(reference.get(k), mt.get(k));
                Trie node = reference.find(k);
                assertEquals(node == null ? Uint24.ZERO : node.getValueLength(), mt.getValueLength(k));
                assertEquals(node == null ? Optional.empty() : Optional.of(node.getValueHash()), mt.getValueHash(k));
            }
        }
        assertEquals(reference.getHash(), mt.getHash());
    }
}
