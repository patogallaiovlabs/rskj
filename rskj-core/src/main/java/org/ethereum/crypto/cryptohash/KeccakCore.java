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

// $Id: KeccakCore.java 258 2011-07-15 22:16:50Z tp $

package org.ethereum.crypto.cryptohash;

import java.lang.invoke.MethodHandles;
import java.lang.invoke.VarHandle;
import java.nio.ByteOrder;

/**
 * This class implements the core operations for the Keccak digest
 * algorithm.
 *
 * <p>The Keccak-f[1600] permutation keeps the 25 lanes in local variables for
 * the 24 rounds (same structure as BouncyCastle's KeccakDigest), so the state
 * array is only read once and written once per absorbed block. The state is
 * kept in plain (non lane-complemented) form.</p>
 *
 * <pre>
 * ==========================(LICENSE BEGIN)============================
 *
 * Copyright (c) 2007-2010  Projet RNRT SAPHIR
 *
 * Permission is hereby granted, free of charge, to any person obtaining
 * a copy of this software and associated documentation files (the
 * "Software"), to deal in the Software without restriction, including
 * without limitation the rights to use, copy, modify, merge, publish,
 * distribute, sublicense, and/or sell copies of the Software, and to
 * permit persons to whom the Software is furnished to do so, subject to
 * the following conditions:
 *
 * The above copyright notice and this permission notice shall be
 * included in all copies or substantial portions of the Software.
 *
 * THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND,
 * EXPRESS OR IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF
 * MERCHANTABILITY, FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT.
 * IN NO EVENT SHALL THE AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY
 * CLAIM, DAMAGES OR OTHER LIABILITY, WHETHER IN AN ACTION OF CONTRACT,
 * TORT OR OTHERWISE, ARISING FROM, OUT OF OR IN CONNECTION WITH THE
 * SOFTWARE OR THE USE OR OTHER DEALINGS IN THE SOFTWARE.
 *
 * ===========================(LICENSE END)=============================
 * </pre>
 *
 * @version   $Revision: 258 $
 * @author    Thomas Pornin &lt;thomas.pornin@cryptolog.com&gt;
 */

abstract class KeccakCore extends DigestEngine {

	KeccakCore()
	{
	}

	private static final VarHandle LE_LONG =
			MethodHandles.byteArrayViewVarHandle(long[].class, ByteOrder.LITTLE_ENDIAN);

	private long[] a;

	private static final long[] RC = {
		0x0000000000000001L, 0x0000000000008082L,
		0x800000000000808AL, 0x8000000080008000L,
		0x000000000000808BL, 0x0000000080000001L,
		0x8000000080008081L, 0x8000000000008009L,
		0x000000000000008AL, 0x0000000000000088L,
		0x0000000080008009L, 0x000000008000000AL,
		0x000000008000808BL, 0x800000000000008BL,
		0x8000000000008089L, 0x8000000000008003L,
		0x8000000000008002L, 0x8000000000000080L,
		0x000000000000800AL, 0x800000008000000AL,
		0x8000000080008081L, 0x8000000000008080L,
		0x0000000080000001L, 0x8000000080008008L
	};

	/** @see org.ethereum.crypto.cryptohash.DigestEngine */
	protected void engineReset()
	{
		doReset();
	}

	/** @see org.ethereum.crypto.cryptohash.DigestEngine */
	protected void processBlock(byte[] data)
	{
		long[] s = a;
		int lanes = data.length >>> 3;
		for (int i = 0; i < lanes; i++) {
			s[i] ^= (long) LE_LONG.get(data, i << 3);
		}
		keccakF1600(s);
	}

	/**
	 * Applies the Keccak-f[1600] permutation in place to the 25-lane state.
	 */
	static void keccakF1600(long[] s)
	{
		long a00 = s[ 0], a01 = s[ 1], a02 = s[ 2], a03 = s[ 3], a04 = s[ 4];
		long a05 = s[ 5], a06 = s[ 6], a07 = s[ 7], a08 = s[ 8], a09 = s[ 9];
		long a10 = s[10], a11 = s[11], a12 = s[12], a13 = s[13], a14 = s[14];
		long a15 = s[15], a16 = s[16], a17 = s[17], a18 = s[18], a19 = s[19];
		long a20 = s[20], a21 = s[21], a22 = s[22], a23 = s[23], a24 = s[24];

		for (int i = 0; i < 24; i++) {
			// theta
			long c0 = a00 ^ a05 ^ a10 ^ a15 ^ a20;
			long c1 = a01 ^ a06 ^ a11 ^ a16 ^ a21;
			long c2 = a02 ^ a07 ^ a12 ^ a17 ^ a22;
			long c3 = a03 ^ a08 ^ a13 ^ a18 ^ a23;
			long c4 = a04 ^ a09 ^ a14 ^ a19 ^ a24;

			long d1 = (c1 << 1 | c1 >>> 63) ^ c4;
			long d2 = (c2 << 1 | c2 >>> 63) ^ c0;
			long d3 = (c3 << 1 | c3 >>> 63) ^ c1;
			long d4 = (c4 << 1 | c4 >>> 63) ^ c2;
			long d0 = (c0 << 1 | c0 >>> 63) ^ c3;

			a00 ^= d1; a05 ^= d1; a10 ^= d1; a15 ^= d1; a20 ^= d1;
			a01 ^= d2; a06 ^= d2; a11 ^= d2; a16 ^= d2; a21 ^= d2;
			a02 ^= d3; a07 ^= d3; a12 ^= d3; a17 ^= d3; a22 ^= d3;
			a03 ^= d4; a08 ^= d4; a13 ^= d4; a18 ^= d4; a23 ^= d4;
			a04 ^= d0; a09 ^= d0; a14 ^= d0; a19 ^= d0; a24 ^= d0;

			// rho / pi
			c1  = a01 <<  1 | a01 >>> 63;
			a01 = a06 << 44 | a06 >>> 20;
			a06 = a09 << 20 | a09 >>> 44;
			a09 = a22 << 61 | a22 >>>  3;
			a22 = a14 << 39 | a14 >>> 25;
			a14 = a20 << 18 | a20 >>> 46;
			a20 = a02 << 62 | a02 >>>  2;
			a02 = a12 << 43 | a12 >>> 21;
			a12 = a13 << 25 | a13 >>> 39;
			a13 = a19 <<  8 | a19 >>> 56;
			a19 = a23 << 56 | a23 >>>  8;
			a23 = a15 << 41 | a15 >>> 23;
			a15 = a04 << 27 | a04 >>> 37;
			a04 = a24 << 14 | a24 >>> 50;
			a24 = a21 <<  2 | a21 >>> 62;
			a21 = a08 << 55 | a08 >>>  9;
			a08 = a16 << 45 | a16 >>> 19;
			a16 = a05 << 36 | a05 >>> 28;
			a05 = a03 << 28 | a03 >>> 36;
			a03 = a18 << 21 | a18 >>> 43;
			a18 = a17 << 15 | a17 >>> 49;
			a17 = a11 << 10 | a11 >>> 54;
			a11 = a07 <<  6 | a07 >>> 58;
			a07 = a10 <<  3 | a10 >>> 61;
			a10 = c1;

			// chi
			c0 = a00 ^ (~a01 & a02);
			c1 = a01 ^ (~a02 & a03);
			a02 ^= ~a03 & a04;
			a03 ^= ~a04 & a00;
			a04 ^= ~a00 & a01;
			a00 = c0;
			a01 = c1;

			c0 = a05 ^ (~a06 & a07);
			c1 = a06 ^ (~a07 & a08);
			a07 ^= ~a08 & a09;
			a08 ^= ~a09 & a05;
			a09 ^= ~a05 & a06;
			a05 = c0;
			a06 = c1;

			c0 = a10 ^ (~a11 & a12);
			c1 = a11 ^ (~a12 & a13);
			a12 ^= ~a13 & a14;
			a13 ^= ~a14 & a10;
			a14 ^= ~a10 & a11;
			a10 = c0;
			a11 = c1;

			c0 = a15 ^ (~a16 & a17);
			c1 = a16 ^ (~a17 & a18);
			a17 ^= ~a18 & a19;
			a18 ^= ~a19 & a15;
			a19 ^= ~a15 & a16;
			a15 = c0;
			a16 = c1;

			c0 = a20 ^ (~a21 & a22);
			c1 = a21 ^ (~a22 & a23);
			a22 ^= ~a23 & a24;
			a23 ^= ~a24 & a20;
			a24 ^= ~a20 & a21;
			a20 = c0;
			a21 = c1;

			// iota
			a00 ^= RC[i];
		}

		s[ 0] = a00; s[ 1] = a01; s[ 2] = a02; s[ 3] = a03; s[ 4] = a04;
		s[ 5] = a05; s[ 6] = a06; s[ 7] = a07; s[ 8] = a08; s[ 9] = a09;
		s[10] = a10; s[11] = a11; s[12] = a12; s[13] = a13; s[14] = a14;
		s[15] = a15; s[16] = a16; s[17] = a17; s[18] = a18; s[19] = a19;
		s[20] = a20; s[21] = a21; s[22] = a22; s[23] = a23; s[24] = a24;
	}

	/** @see org.ethereum.crypto.cryptohash.DigestEngine */
	protected void doPadding(byte[] out, int off)
	{
		int ptr = flush();
		byte[] buf = getBlockBuffer();
		if ((ptr + 1) == buf.length) {
			buf[ptr] = (byte)0x81;
		} else {
			buf[ptr] = (byte)0x01;
			for (int i = ptr + 1; i < (buf.length - 1); i ++) {
                buf[i] = 0;
            }
			buf[buf.length - 1] = (byte)0x80;
		}
		processBlock(buf);
		int dlen = getDigestLength();
		int full = dlen >>> 3;
		for (int i = 0; i < full; i++) {
			LE_LONG.set(out, off + (i << 3), a[i]);
		}
		int rem = dlen & 7;
		if (rem != 0) {
			long v = a[full];
			int base = off + (full << 3);
			for (int k = 0; k < rem; k++) {
				out[base + k] = (byte) (v >>> (k << 3));
			}
		}
	}

	/** @see org.ethereum.crypto.cryptohash.DigestEngine */
	protected void doInit()
	{
		a = new long[25];
		doReset();
	}

	/** @see org.ethereum.crypto.cryptohash.Digest */
	public int getBlockLength()
	{
		return 200 - 2 * getDigestLength();
	}

	private final void doReset()
	{
		for (int i = 0; i < 25; i ++) {
            a[i] = 0;
        }
	}

	/** @see org.ethereum.crypto.cryptohash.DigestEngine */
	protected Digest copyState(KeccakCore dst)
	{
		System.arraycopy(a, 0, dst.a, 0, 25);
		return super.copyState(dst);
	}

	/** @see org.ethereum.crypto.cryptohash.Digest */
	public String toString()
	{
		return "Keccak-" + (getDigestLength() << 3);
	}
}
