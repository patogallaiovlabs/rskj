package org.ethereum.crypto.jni;

import org.bouncycastle.math.ec.ECPoint;
import org.ethereum.crypto.ECDSASignature;
import org.ethereum.crypto.ECKey;

import javax.annotation.Nullable;
import java.math.BigInteger;

public class ECKeyNative implements ECKey {

    @Override
    public ECKey decompress() {
        return null;
    }

    @Override
    public boolean isPubKeyOnly() {
        return false;
    }

    @Override
    public boolean hasPrivKey() {
        return false;
    }

    @Override
    public byte[] getAddress() {
        return new byte[0];
    }

    @Override
    public byte[] getNodeId() {
        return new byte[0];
    }

    @Override
    public byte[] getPubKey() {
        return new byte[0];
    }

    @Override
    public byte[] getPubKey(boolean compressed) {
        return new byte[0];
    }

    @Override
    public ECPoint getPubKeyPoint() {
        return null;
    }

    @Override
    public BigInteger getPrivKey() {
        return null;
    }

    @Override
    public ECDSASignature doSign(byte[] input) {
        return null;
    }

    @Override
    public ECDSASignature sign(byte[] messageHash) {
        return null;
    }

    @Override
    public byte[] decryptAES(byte[] cipher) {
        return new byte[0];
    }

    @Override
    public boolean isPubKeyCanonical() {
        return false;
    }

    @Nullable
    @Override
    public byte[] getPrivKeyBytes() {
        return new byte[0];
    }
}
