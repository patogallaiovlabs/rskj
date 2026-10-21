/*
 * This file is part of RskJ
 * Copyright (C) 2017 RSK Labs Ltd.
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

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Assertions;

import java.util.Arrays;
import java.util.Random;

/**
 * Created by ajlopez on 07/02/2017.
 */
class PathEncoderTest {
    @Test
    void encodeNullBinaryPath() {
        try {
            PathEncoder.encode(null);
            Assertions.fail();
        }
        catch (Exception ex) {
            Assertions.assertTrue(ex instanceof IllegalArgumentException);
        }
    }

    @Test
    void encodeBinaryPath() {
        byte[] path = new byte[] { 0x00, 0x01, 0x01 };

        byte[] encoded = PathEncoder.encode(path);

        Assertions.assertNotNull(encoded);
        Assertions.assertArrayEquals(new byte[] { 0x60 }, encoded);
    }

    @Test
    void encodeBinaryPathOneByte() {
        byte[] path = new byte[] { 0x00, 0x01, 0x01, 0x00, 0x01, 0x01, 0x00, 0x01 };

        byte[] encoded = PathEncoder.encode(path);

        Assertions.assertNotNull(encoded);
        Assertions.assertArrayEquals(new byte[] { 0x6d }, encoded);
    }

    @Test
    void encodeBinaryPathNineBits() {
        byte[] path = new byte[] { 0x00, 0x01, 0x01, 0x00, 0x01, 0x01, 0x00, 0x01, 0x01 };

        byte[] encoded = PathEncoder.encode(path);

        Assertions.assertNotNull(encoded);
        Assertions.assertArrayEquals(new byte[] { 0x6d, (byte)0x80 }, encoded);
    }

    @Test
    void encodeBinaryPathOneAndHalfByte() {
        byte[] path = new byte[] { 0x00, 0x01, 0x01, 0x00, 0x01, 0x01, 0x00, 0x01, 0x00, 0x01, 0x00, 0x01 };

        byte[] encoded = PathEncoder.encode(path);

        Assertions.assertNotNull(encoded);
        Assertions.assertArrayEquals(new byte[] { 0x6d, 0x50 }, encoded);
    }

    @Test
    void encodeBinaryPathWithOffsetAndLimitMatchesEquivalentStandaloneArray() {
        // the [2, 10) sub-range below is exactly { 0x00, 0x01, 0x01, 0x00, 0x01, 0x01, 0x00, 0x01 },
        // the same bits as encodeBinaryPathOneByte()'s standalone array
        byte[] path = new byte[] { 0x01, 0x00, 0x00, 0x01, 0x01, 0x00, 0x01, 0x01, 0x00, 0x01, 0x01, 0x00 };

        byte[] encoded = PathEncoder.encode(path, 2, 10);

        Assertions.assertNotNull(encoded);
        Assertions.assertArrayEquals(new byte[] { 0x6d }, encoded);
    }

    @Test
    void encodeBinaryPathWithOffsetAndLimitNullPath() {
        try {
            PathEncoder.encode(null, 0, 3);
            Assertions.fail();
        }
        catch (Exception ex) {
            Assertions.assertTrue(ex instanceof IllegalArgumentException);
        }
    }

    @Test
    void decodeNullBinaryPath() {
        try {
            PathEncoder.decode(null, 0);
            Assertions.fail();
        }
        catch (Exception ex) {
            Assertions.assertTrue(ex instanceof IllegalArgumentException);
        }
    }

    @Test
    void decodeBinaryPath() {
        byte[] encoded = new byte[] { 0x60 };

        byte[] path = PathEncoder.decode(encoded, 3);

        Assertions.assertNotNull(path);
        Assertions.assertArrayEquals(new byte[] { 0x00, 0x01, 0x01 }, path);
    }

    @Test
    void decodeBinaryPathOneByte() {
        byte[] encoded = new byte[] { 0x6d };

        byte[] path = PathEncoder.decode(encoded, 8);

        Assertions.assertNotNull(path);
        Assertions.assertArrayEquals(new byte[] { 0x00, 0x01, 0x01, 0x00, 0x01, 0x01, 0x00, 0x01 }, path);
    }

    @Test
    void decodeBinaryPathNineBits() {
        byte[] encoded = new byte[] { 0x6d, (byte)0x80 };

        byte[] path = PathEncoder.decode(encoded, 9);

        Assertions.assertNotNull(path);
        Assertions.assertArrayEquals(new byte[] { 0x00, 0x01, 0x01, 0x00, 0x01, 0x01, 0x00, 0x01, 0x01 }, path);
    }

    @Test
    void decodeBinaryPathOneAndHalfByte() {
        byte[] encoded = new byte[] { 0x6d, 0x50 };

        byte[] path = PathEncoder.decode(encoded, 12);

        Assertions.assertNotNull(path);
        Assertions.assertArrayEquals(new byte[] { 0x00, 0x01, 0x01, 0x00, 0x01, 0x01, 0x00, 0x01, 0x00, 0x01, 0x00, 0x01 }, path);
    }

    // Reference copies of the original per-bit implementations, used to pin behavior.
    private static byte[] referenceEncode(byte[] path, int start, int limit) {
        int lpath = limit - start;
        byte[] encoded = new byte[PathEncoder.calculateEncodedLength(lpath)];
        int nbyte = 0;
        for (int k = 0; k < lpath; k++) {
            int offset = k % 8;
            if (k > 0 && offset == 0) {
                nbyte++;
            }
            if (path[start + k] == 0) {
                continue;
            }
            encoded[nbyte] |= 0x80 >> offset;
        }
        return encoded;
    }

    private static byte[] referenceDecode(byte[] encoded, int bitlength) {
        byte[] path = new byte[bitlength];
        for (int k = 0; k < bitlength; k++) {
            if (((encoded[k / 8] >> (7 - k % 8)) & 0x01) != 0) {
                path[k] = 1;
            }
        }
        return path;
    }

    @Test
    void encodeDecodeMatchReferenceForAllLengths() {
        Random random = new Random(42);
        for (int len = 0; len <= 300; len++) {
            for (int pattern = 0; pattern < 8; pattern++) {
                byte[] path = new byte[len];
                for (int i = 0; i < len; i++) {
                    switch (pattern) {
                        case 0: path[i] = 0; break;
                        case 1: path[i] = 1; break;
                        case 2: path[i] = (byte) (i % 2); break;
                        case 3: path[i] = (byte) ((i + 1) % 2); break;
                        case 4: path[i] = (byte) (i == len - 1 ? 1 : 0); break;
                        case 5: path[i] = (byte) (i == 0 ? 1 : 0); break;
                        // non-binary values: any non-zero element encodes as a 1 bit
                        case 6: path[i] = (byte) random.nextInt(); break;
                        default: path[i] = (byte) random.nextInt(2); break;
                    }
                }

                byte[] encoded = PathEncoder.encode(path);
                Assertions.assertArrayEquals(referenceEncode(path, 0, len), encoded, "encode len=" + len + " pattern=" + pattern);

                if (len > 0) {
                    int off = random.nextInt(len);
                    int lim = off + random.nextInt(len - off + 1);
                    Assertions.assertArrayEquals(referenceEncode(path, off, lim), PathEncoder.encode(path, off, lim),
                            "encode range len=" + len + " off=" + off + " lim=" + lim);
                }

                Assertions.assertArrayEquals(referenceDecode(encoded, len), PathEncoder.decode(encoded, len),
                        "decode len=" + len + " pattern=" + pattern);
            }

            // decode of arbitrary bytes, including set pad bits and sign-bit bytes
            byte[] raw = new byte[PathEncoder.calculateEncodedLength(len) + 1];
            random.nextBytes(raw);
            Assertions.assertArrayEquals(referenceDecode(raw, len), PathEncoder.decode(raw, len), "raw decode len=" + len);
            Arrays.fill(raw, (byte) 0xff);
            Assertions.assertArrayEquals(referenceDecode(raw, len), PathEncoder.decode(raw, len), "ff decode len=" + len);
        }
    }

    @Test
    void decodeWithTooShortEncodedThrows() {
        Assertions.assertThrows(ArrayIndexOutOfBoundsException.class, () -> PathEncoder.decode(new byte[1], 9));
        Assertions.assertThrows(ArrayIndexOutOfBoundsException.class, () -> PathEncoder.decode(new byte[1], 16));
    }
}
