/*
 * This file is part of RskJ
 * Copyright (C) 2017 RSK Labs Ltd.
 * (derived from ethereumJ library, Copyright (c) 2016 <ether.camp>)
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

import co.rsk.core.types.bytes.Bytes;
import org.bouncycastle.crypto.digests.KeccakDigest;
import org.bouncycastle.util.encoders.Hex;
import org.ethereum.crypto.cryptohash.Keccak256;
import org.ethereum.util.ByteUtil;

import java.math.BigInteger;

public class Keccak256Helper {

    public static final int DEFAULT_SIZE = 256;
    public static final int DEFAULT_SIZE_BYTES = DEFAULT_SIZE / 8;

    // 256-bit default hashing uses the same legacy Keccak-256 implementation as HashUtil
    // (0x01 padding, rate 1088), byte-identical to BouncyCastle's KeccakDigest(256).
    // digest() resets the instance, so one per-thread instance is safe to reuse; on an
    // exception (e.g. bad bounds) it is reset explicitly so no partial state leaks.
    private static final ThreadLocal<Keccak256> FAST_DIGEST = ThreadLocal.withInitial(Keccak256::new);

    private static byte[] fastKeccak256(byte[] message, int start, int length) {
        Keccak256 digest = FAST_DIGEST.get();
        try {
            if (message.length != 0) {
                digest.update(Bytes.of(message), start, length);
            }
            return digest.digest();
        } catch (RuntimeException e) {
            digest.reset();
            throw e;
        }
    }

    public static String keccak256String(String message) {
        if (message == null) {
            throw new NullPointerException("Can't hash a NULL value");
        }
        return keccak256String(Hex.decode(message));
    }

    public static String keccak256String(byte[] message) {
        return ByteUtil.toHexString(keccak256(message));
    }

    public static byte[] keccak256(String message) {
        return keccak256(Hex.decode(message));
    }

    public static byte[] keccak256(byte[] message) {
        return fastKeccak256(message, 0, message.length);
    }

    public static byte[] keccak256(byte[] message, Size sz) {
        return keccak256(message, new KeccakDigest(sz.bits), true);
    }

    public static byte[] keccak256(byte[] m1, byte[] m2) {
        Keccak256 digest = FAST_DIGEST.get();
        if (m1.length != 0) {
            digest.update(Bytes.of(m1), 0, m1.length);
        }
        if (m2.length != 0) {
            digest.update(Bytes.of(m2), 0, m2.length);
        }
        // full-array updates cannot throw, so no reset-on-failure is needed here
        return digest.digest();
    }

    public static byte[] keccak256(byte[] message, int start, int length) {
        return fastKeccak256(message, start, length);
    }

    protected static String keccak256String(String message, Size bitSize) {
        KeccakDigest digest = new KeccakDigest(bitSize.bits);
        return keccak256String(message, digest, true);
    }

    protected static String keccak256String(byte[] message, Size bitSize) {
        KeccakDigest digest = new KeccakDigest(bitSize.bits);
        return keccak256String(message, digest, true);
    }

    protected static String keccak256String(String message, Size bitSize, boolean bouncyencoder) {
        KeccakDigest digest = new KeccakDigest(bitSize.bits);
        return keccak256String(message, digest, bouncyencoder);
    }

    protected static String keccak256String(byte[] message, Size bitSize, boolean bouncyencoder) {
        KeccakDigest digest = new KeccakDigest(bitSize.bits);
        return keccak256String(message, digest, bouncyencoder);
    }

    private static String keccak256String(String message, KeccakDigest digest, boolean bouncyencoder) {
        if (message != null) {
            return keccak256String(Hex.decode(message), digest, bouncyencoder);
        }
        throw new NullPointerException("Can't hash a NULL value");
    }

    private static String keccak256String(byte[] message, KeccakDigest digest, boolean bouncyencoder) {
        byte[] hash = doKeccak256(message, digest, bouncyencoder);
        if (bouncyencoder) {
            return ByteUtil.toHexString(hash);
        } else {
            BigInteger bigInt = new BigInteger(1, hash);
            return bigInt.toString(16);
        }
    }

    private static byte[] keccak256(byte[] message, KeccakDigest digest, boolean bouncyencoder) {
        return doKeccak256(message, digest, bouncyencoder);
    }

    private static byte[] keccak256(byte[] message, int start, int length, KeccakDigest digest, boolean bouncyencoder) {
        byte[] hash = new byte[digest.getDigestSize()];

        if (message.length != 0) {
            digest.update(message, start, length);
        }
        digest.doFinal(hash, 0);
        return hash;
    }


    private static byte[] doKeccak256(byte[] message, KeccakDigest digest, boolean bouncyencoder) {
        byte[] hash = new byte[digest.getDigestSize()];

        if (message.length != 0) {
            digest.update(message, 0, message.length);
        }
        digest.doFinal(hash, 0);
        return hash;
    }

    public enum Size {

        S224(224),
        S256(256),
        S384(384),
        S512(512);

        int bits = 0;

        Size(int bits) {
            this.bits = bits;
        }

        public int getValue() {
            return this.bits;
        }
    }

}
