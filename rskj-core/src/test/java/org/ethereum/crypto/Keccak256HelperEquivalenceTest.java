/*
 * This file is part of RskJ
 * Copyright (C) 2024 RSK Labs Ltd.
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

package org.ethereum.crypto;

import org.bouncycastle.crypto.digests.KeccakDigest;
import org.bouncycastle.util.encoders.Hex;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.Random;

class Keccak256HelperEquivalenceTest {

    private static byte[] bc(byte[] data, int start, int len) {
        KeccakDigest d = new KeccakDigest(256);
        d.update(data, start, len);
        byte[] out = new byte[32];
        d.doFinal(out, 0);
        return out;
    }

    @Test
    void emptyInputVector() {
        Assertions.assertEquals("c5d2460186f7233c927e7db2dcc703c0e500b653ca82273b7bfad8045d85a470",
                Hex.toHexString(Keccak256Helper.keccak256(new byte[0])));
    }

    @Test
    void matchesBouncyCastleOnRandomLengths() {
        Random r = new Random(42);
        for (int len = 0; len <= 1000; len++) {
            byte[] data = new byte[len];
            r.nextBytes(data);
            byte[] expected = bc(data, 0, len);
            Assertions.assertArrayEquals(expected, Keccak256Helper.keccak256(data), "len " + len);
            Assertions.assertArrayEquals(expected, Keccak256Helper.keccak256(data, 0, len), "len " + len);
            Assertions.assertArrayEquals(expected, HashUtil.keccak256(data), "HashUtil len " + len);
            Assertions.assertEquals(Hex.toHexString(expected), Keccak256Helper.keccak256String(data));

            if (len > 2) {
                int start = 1;
                int sub = len - 2;
                Assertions.assertArrayEquals(bc(data, start, sub), Keccak256Helper.keccak256(data, start, sub));
                byte[] a = Arrays.copyOfRange(data, 0, len / 2);
                byte[] b = Arrays.copyOfRange(data, len / 2, len);
                Assertions.assertArrayEquals(expected, Keccak256Helper.keccak256(a, b), "concat len " + len);
            }
        }
    }

    @Test
    void emptyPartsInConcatenation() {
        byte[] data = new byte[]{1, 2, 3};
        byte[] expected = bc(data, 0, 3);
        Assertions.assertArrayEquals(expected, Keccak256Helper.keccak256(new byte[0], data));
        Assertions.assertArrayEquals(expected, Keccak256Helper.keccak256(data, new byte[0]));
    }

    @Test
    void stringHelpersMatchBouncyCastle() {
        byte[] data = Hex.decode("deadbeef00");
        Assertions.assertArrayEquals(bc(data, 0, data.length), Keccak256Helper.keccak256("deadbeef00"));
        Assertions.assertEquals(Hex.toHexString(bc(data, 0, data.length)), Keccak256Helper.keccak256String("deadbeef00"));
        Assertions.assertThrows(NullPointerException.class, () -> Keccak256Helper.keccak256String((String) null));
    }

    @Test
    void outOfBoundsDoesNotPoisonThreadLocalDigest() {
        byte[] data = new byte[300];
        new Random(7).nextBytes(data);
        Assertions.assertThrows(RuntimeException.class, () -> Keccak256Helper.keccak256(data, 0, 400));
        Assertions.assertArrayEquals(bc(data, 0, 300), Keccak256Helper.keccak256(data));
    }

    @Test
    void emptyTrieHash() {
        // RLP of empty byte array is 0x80
        Assertions.assertArrayEquals(bc(new byte[]{(byte) 0x80}, 0, 1), HashUtil.EMPTY_TRIE_HASH);
    }
}
