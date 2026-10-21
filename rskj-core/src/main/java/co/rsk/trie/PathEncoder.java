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

import javax.annotation.Nonnull;

/**
 * Created by martin.medina on 5/04/17.
 */
public class PathEncoder {
    private PathEncoder() { }

    @Nonnull
    public static byte[] encode(byte[] path) {
        if (path == null) {
            throw new IllegalArgumentException("path");
        }

        return encodeBinaryPath(path, 0, path.length);
    }

    /**
     * Same as encode(byte[]), but reads directly from a [offset, limit) sub-range of a
     * shared array instead of requiring the caller to pass an already-copied sub-array.
     * Callers holding a slice of a larger array (e.g. TrieKeySlice) can encode without an
     * intermediate Arrays.copyOfRange -- this is called once per trie node during
     * serialization, so avoiding that allocation+copy matters.
     */
    @Nonnull
    public static byte[] encode(byte[] path, int offset, int limit) {
        if (path == null) {
            throw new IllegalArgumentException("path");
        }

        return encodeBinaryPath(path, offset, limit);
    }

    @Nonnull
    public static byte[] decode(byte[] encoded, int length) {
        if (encoded == null) {
            throw new IllegalArgumentException("encoded");
        }

        return decodeBinaryPath(encoded, length);
    }

    @Nonnull
    // First bit is MOST SIGNIFICANT
    private static byte[] encodeBinaryPath(byte[] path, int start, int limit) {
        int lpath = limit - start;
        int lencoded = calculateEncodedLength(lpath);

        byte[] encoded = new byte[lencoded];

        // Whole bytes: pack 8 path bits per output byte, MSB first. Any non-zero path
        // element is a 1 bit (same as the original "== 0 ? skip : set" test).
        int nbyte = 0;
        int k = 0;
        int p = start;
        for (; k + 8 <= lpath; k += 8, p += 8, nbyte++) {
            int b = (path[p] != 0 ? 0x80 : 0)
                    | (path[p + 1] != 0 ? 0x40 : 0)
                    | (path[p + 2] != 0 ? 0x20 : 0)
                    | (path[p + 3] != 0 ? 0x10 : 0)
                    | (path[p + 4] != 0 ? 0x08 : 0)
                    | (path[p + 5] != 0 ? 0x04 : 0)
                    | (path[p + 6] != 0 ? 0x02 : 0)
                    | (path[p + 7] != 0 ? 0x01 : 0);
            encoded[nbyte] = (byte) b;
        }

        // Trailing partial byte (lpath % 8 bits); unused low bits stay zero.
        if (k < lpath) {
            int b = 0;
            for (int offset = 0; k < lpath; k++, p++, offset++) {
                if (path[p] != 0) {
                    b |= 0x80 >> offset;
                }
            }
            encoded[nbyte] = (byte) b;
        }

        return encoded;
    }

    @Nonnull
    // length is the length in bits. For example ({1},8) is fine
    // First bit is MOST SIGNIFICANT
    private static byte[] decodeBinaryPath(byte[] encoded, int bitlength) {
        byte[] path = new byte[bitlength];

        // Whole bytes: expand each encoded byte into 8 path elements, MSB first.
        int k = 0;
        int nbyte = 0;
        for (; k + 8 <= bitlength; k += 8, nbyte++) {
            int b = encoded[nbyte];
            path[k] = (byte) ((b >>> 7) & 1);
            path[k + 1] = (byte) ((b >>> 6) & 1);
            path[k + 2] = (byte) ((b >>> 5) & 1);
            path[k + 3] = (byte) ((b >>> 4) & 1);
            path[k + 4] = (byte) ((b >>> 3) & 1);
            path[k + 5] = (byte) ((b >>> 2) & 1);
            path[k + 6] = (byte) ((b >>> 1) & 1);
            path[k + 7] = (byte) (b & 1);
        }

        // Trailing partial byte (bitlength % 8 bits).
        if (k < bitlength) {
            int b = encoded[nbyte];
            for (int offset = 7; k < bitlength; k++, offset--) {
                path[k] = (byte) ((b >>> offset) & 1);
            }
        }

        return path;
    }

    public static int calculateEncodedLength(int keyLength) {
        return keyLength / 8 + (keyLength % 8 == 0 ? 0 : 1);
    }
}
