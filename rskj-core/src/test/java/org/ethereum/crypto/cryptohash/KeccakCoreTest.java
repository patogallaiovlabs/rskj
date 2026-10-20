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
package org.ethereum.crypto.cryptohash;

import co.rsk.core.types.bytes.Bytes;
import org.bouncycastle.crypto.digests.KeccakDigest;
import org.bouncycastle.util.encoders.Hex;
import org.ethereum.crypto.HashUtil;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.Random;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;

class KeccakCoreTest {

    private static byte[] reference(byte[] input) {
        KeccakDigest d = new KeccakDigest(256);
        d.update(input, 0, input.length);
        byte[] out = new byte[32];
        d.doFinal(out, 0);
        return out;
    }

    private static byte[] filled(int len) {
        byte[] b = new byte[len];
        for (int i = 0; i < len; i++) {
            b[i] = (byte) i;
        }
        return b;
    }

    @Test
    void knownAnswers() {
        assertEquals("c5d2460186f7233c927e7db2dcc703c0e500b653ca82273b7bfad8045d85a470",
                Hex.toHexString(new Keccak256().digest(new byte[0])));
        assertEquals("4e03657aea45a94fc7d47ba826c8d667c0d1e6e33a64a036ec44f58fa12d6c45",
                Hex.toHexString(new Keccak256().digest("abc".getBytes(StandardCharsets.US_ASCII))));
        // 0x00..0x(len-1) around the 136-byte rate boundary, checked against BouncyCastle
        for (int len : new int[]{0, 1, 31, 32, 33, 135, 136, 137, 271, 272, 273, 1000}) {
            byte[] in = filled(len);
            assertArrayEquals(reference(in), new Keccak256().digest(in), "len=" + len);
            assertArrayEquals(reference(in), HashUtil.keccak256(in), "len=" + len);
        }
    }

    @Test
    void randomizedDifferentialAgainstBouncyCastle() {
        Random rnd = new Random(0xC0FFEEL);
        Keccak256 reused = new Keccak256();
        for (int n = 0; n < 100_000; n++) {
            byte[] in = new byte[rnd.nextInt(2001)];
            rnd.nextBytes(in);
            byte[] expected = reference(in);
            assertArrayEquals(expected, reused.digest(in), "n=" + n);
            assertArrayEquals(expected, HashUtil.keccak256(in), "n=" + n);
        }
    }

    @Test
    void incrementalUpdatesSlicesAndCopy() {
        Random rnd = new Random(7);
        for (int n = 0; n < 2_000; n++) {
            byte[] in = new byte[rnd.nextInt(700)];
            rnd.nextBytes(in);
            byte[] expected = reference(in);

            Keccak256 k = new Keccak256();
            int split = in.length == 0 ? 0 : rnd.nextInt(in.length + 1);
            for (int i = 0; i < split; i++) {
                k.update(in[i]);
            }
            Digest copy = k.copy();
            k.update(Bytes.of(in), split, in.length - split);
            assertArrayEquals(expected, k.digest(), "n=" + n);

            copy.update(Bytes.of(in), split, in.length - split);
            assertArrayEquals(expected, copy.digest(), "copy n=" + n);

            // truncated output path
            byte[] out = new byte[20];
            Keccak256 t = new Keccak256();
            t.update(Bytes.of(in));
            assertEquals(20, t.digest(out, 0, 20));
            byte[] head = new byte[20];
            System.arraycopy(expected, 0, head, 0, 20);
            assertArrayEquals(head, out);

            // offset output path
            byte[] big = new byte[40];
            Keccak256 o = new Keccak256();
            o.update(Bytes.of(in));
            o.digest(big, 5, 32);
            byte[] mid = new byte[32];
            System.arraycopy(big, 5, mid, 0, 32);
            assertArrayEquals(expected, mid);
        }
    }
}
