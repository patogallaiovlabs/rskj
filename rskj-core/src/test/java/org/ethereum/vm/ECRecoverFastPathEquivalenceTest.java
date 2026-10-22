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

package org.ethereum.vm;

import org.bitcoin.Secp256k1Context;
import org.bouncycastle.util.BigIntegers;
import org.ethereum.config.Constants;
import org.ethereum.crypto.ECKey;
import org.ethereum.crypto.HashUtil;
import org.ethereum.crypto.signature.ECDSASignature;
import org.ethereum.crypto.signature.Secp256k1;
import org.ethereum.crypto.signature.Secp256k1Service;
import org.ethereum.crypto.signature.Secp256k1ServiceNative;
import org.junit.jupiter.api.Test;

import java.math.BigInteger;
import java.util.Random;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * Pins that {@link Secp256k1ServiceNative#recoverAddressFast} (used by the ECRecover precompile) returns exactly
 * what the regular signatureToKey(...).getAddress() path returns, and returns null (fallback) for invalid inputs.
 */
class ECRecoverFastPathEquivalenceTest {

    private static final BigInteger N = Constants.getSECP256K1N();

    private final Secp256k1ServiceNative nativeService = new Secp256k1ServiceNative();
    // Bouncy Castle unless some other test initialized the singleton; either way it is the regular path.
    private final Secp256k1Service bcService = Secp256k1.getInstance();

    private static byte[] b32(BigInteger x) {
        return BigIntegers.asUnsignedByteArray(32, x);
    }

    @Test
    void invalidComponentsFallBack() {
        byte[] h = HashUtil.keccak256(new byte[]{1});
        byte[] one = b32(BigInteger.ONE);
        byte[] zero = new byte[32];
        byte[] n = b32(N);
        byte[] max = new byte[32];
        java.util.Arrays.fill(max, (byte) 0xff);

        // invalid v
        for (int v : new int[]{0, 1, 26, 29, 30, 31, 34, -1}) {
            assertNull(nativeService.recoverAddressFast(h, one, one, (byte) v));
        }
        // r or s out of [1, N)
        assertNull(nativeService.recoverAddressFast(h, zero, one, (byte) 27));
        assertNull(nativeService.recoverAddressFast(h, one, zero, (byte) 27));
        assertNull(nativeService.recoverAddressFast(h, n, one, (byte) 27));
        assertNull(nativeService.recoverAddressFast(h, one, n, (byte) 28));
        assertNull(nativeService.recoverAddressFast(h, max, one, (byte) 28));
        assertNull(nativeService.recoverAddressFast(h, one, max, (byte) 27));
    }

    @Test
    void realSignaturesMatchRegularPath() {
        assumeTrue(Secp256k1Context.isEnabled());
        Random random = new Random(42);
        for (int i = 0; i < 200; i++) {
            byte[] privBytes = new byte[32];
            random.nextBytes(privBytes);
            ECKey key = ECKey.fromPrivate(privBytes);
            byte[] msg = new byte[random.nextInt(64) + 1];
            random.nextBytes(msg);
            byte[] h = HashUtil.keccak256(msg);
            ECKey.ECDSASignature sig = key.sign(h);
            byte[] r = b32(sig.r);
            byte[] s = b32(sig.s);

            byte[] fast = nativeService.recoverAddressFast(h, r, s, sig.v);
            assertNotNull(fast);
            assertArrayEquals(key.getAddress(), fast);
            assertSameAsRegular(h, r, s, sig.v, fast);
        }
    }

    @Test
    void arbitraryComponentsMatchRegularPath() throws Exception {
        assumeTrue(Secp256k1Context.isEnabled());
        Random random = new Random(7);
        BigInteger[] edges = {BigInteger.ONE, BigInteger.TWO, N.subtract(BigInteger.ONE), N.subtract(BigInteger.TWO),
                N.shiftRight(1), N.shiftRight(1).add(BigInteger.ONE)};
        for (BigInteger re : edges) {
            for (BigInteger se : edges) {
                for (byte v : new byte[]{27, 28}) {
                    byte[] h = new byte[32];
                    random.nextBytes(h);
                    check(h, b32(re), b32(se), v);
                }
            }
        }
        for (int i = 0; i < 300; i++) {
            byte[] h = new byte[32];
            byte[] r = new byte[32];
            byte[] s = new byte[32];
            random.nextBytes(h);
            random.nextBytes(r);
            random.nextBytes(s);
            check(h, r, s, (byte) (27 + (i & 1)));
        }
    }

    private void check(byte[] h, byte[] r, byte[] s, byte v) {
        byte[] fast = nativeService.recoverAddressFast(h, r, s, v);
        if (fast != null) {
            assertSameAsRegular(h, r, s, v, fast);
        }
    }

    private void assertSameAsRegular(byte[] h, byte[] r, byte[] s, byte v, byte[] fast) {
        ECDSASignature signature = ECDSASignature.fromComponents(r, s, v);
        try {
            assertArrayEquals(nativeService.signatureToKey(h, signature).getAddress(), fast);
            assertArrayEquals(bcService.signatureToKey(h, signature).getAddress(), fast);
        } catch (java.security.SignatureException e) {
            throw new AssertionError("regular path failed where fast path succeeded", e);
        }
    }
}
