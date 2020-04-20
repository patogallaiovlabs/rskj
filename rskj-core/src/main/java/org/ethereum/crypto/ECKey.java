/*
 * This file is part of RskJ
 * Copyright (C) 2017 RSK Labs Ltd.
 * (derived from ethereumJ library, Copyright (c) 2016 <ether.camp>)
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Lesser General License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the
 * GNU Lesser General License for more details.
 *
 * You should have received a copy of the GNU Lesser General License
 * along with this program. If not, see <http://www.gnu.org/licenses/>.
 */

package org.ethereum.crypto;
/**
 * Copyright 2011 Google Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *    http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

import org.bouncycastle.math.ec.ECPoint;

import javax.annotation.Nullable;
import java.math.BigInteger;

/**
 * <p>Represents an elliptic curve and (optionally) private key, usable for digital signatures but not encryption.
 * Creating a new ECKey with the empty constructor will generate a new random keypair. Other methods can be used
 * when you already have the or private parts. If you create a key with only the part, you can check
 * signatures but not create them.</p>
 *
 * <p>The ECDSA algorithm supports <i>key recovery</i> in which a signature plus a couple of discriminator bits can
 * be reversed to find the key used to calculate it. This can be convenient when you have a message and a
 * signature and want to find out who signed it, rather than requiring the user to provide the expected identity.</p>
 *
 * <p>A key can be <i>compressed</i> or <i>uncompressed</i>. This refers to whether the key is represented
 * when encoded into bytes as an (x, y) coordinate on the elliptic curve, or whether it's represented as just an X
 * co-ordinate and an extra byte that carries a sign bit. With the latter form the Y coordinate can be calculated
 * dynamically, however, <b>because the binary serialization is different the address of a key changes if its
 * compression status is changed</b>. If you deviate from the defaults it's important to understand this: money sent
 * to a compressed version of the key will have a different address to the same key in uncompressed form. Whether
 * a key is compressed or not is recorded in the SEC binary serialisation format, and preserved in a flag in
 * this class so round-tripping preserves state. Unless you're working with old software or doing unusual things, you
 * can usually ignore the compressed/uncompressed distinction.</p>
 *
 * This code is borrowed from the bitcoinj project and altered to fit Ethereum.<br>
 * See <a href="https://github.com/bitcoinj/bitcoinj/blob/master/core/src/main/java/com/google/bitcoin/core/ECKey.java">
 * bitcoinj on GitHub</a>.
 */
public interface ECKey {

    /**
     * Returns a copy of this key, but with the point represented in uncompressed form. Normally you would
     * never need this: it's for specialised scenarios or when backwards compatibility in encoded form is necessary.
     *
     * @return  -
     */

     ECKey decompress();

    /**
     * Returns true if this key doesn't have access to private key bytes. This may be because it was never
     * given any private key bytes to begin with (a watching key).
     *
     * @return -
     */
     boolean isPubKeyOnly();

    /**
     * Returns true if this key has access to private key bytes. Does the opposite of
     * {@link #isPubKeyOnly()}.
     *
     * @return  -
     */
    boolean hasPrivKey();

    /**
     * Gets the hash160 form of the key (as seen in addresses).
     *
     * @return -
     */
    byte[] getAddress();

    /**
     * Generates the NodeID based on this key, that is the key without first format byte
     */
    byte[] getNodeId();

    /**
     * Gets the raw key value. This appears in transaction scriptSigs. Note that this is <b>not</b> the same
     * as the pubKeyHash/address.
     *
     * @return  -
     */
    byte[] getPubKey();

    byte[] getPubKey(boolean compressed);

    /**
     * Gets the key in the form of an elliptic curve point object from Bouncy Castle.
     *
     * @return  -
     */
    ECPoint getPubKeyPoint();

    /**
     * Gets the private key in the form of an integer field element. The key is derived by performing EC
     * point addition this number of times (i.e. point multiplying).
     *
     *
     * @return  -
     *
     * @throws java.lang.IllegalStateException if the private key bytes are not available.
     */
    BigInteger getPrivKey();

    String toString();

    /**
     * Signs the given hash and returns the R and S components as BigIntegers
     * and put them in ECDSASignature
     *
     * @param input to sign
     * @return ECDSASignature signature that contains the R and S components
     */
    ECDSASignature doSign(byte[] input);


    /**
     * Takes the sha3 hash (32 bytes) of data and returns the ECDSA signature
     *
     * @param messageHash -
     * @return -
     * @throws IllegalStateException if this ECKey does not have the private part.
     */
    ECDSASignature sign(byte[] messageHash);

    /**
     * Decrypt cipher by AES in SIC(also know as CTR) mode
     *
     * @param cipher -proper cipher
     * @return decrypted cipher, equal length to the cipher.
     */
    byte[] decryptAES(byte[] cipher);

    /**
     * Returns true if this pubkey is canonical, i.e. the correct length taking into account compression.
     *
     * @return -
     */
    boolean isPubKeyCanonical();

    /**
     * Returns a 32 byte array containing the private key, or null if the key is encrypted or only
     *
     *  @return  -
     */
    @Nullable
    byte[] getPrivKeyBytes();

    @Override
    boolean equals(Object o);

    @Override
    int hashCode();


}
