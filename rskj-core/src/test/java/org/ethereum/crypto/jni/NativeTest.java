package org.ethereum.crypto.jni;

import org.bitcoin.NativeSecp256k1;
import org.bitcoin.NativeSecp256k1Util;
import org.bouncycastle.util.encoders.Hex;
import org.ethereum.crypto.ECDSASignature;
import org.ethereum.crypto.ECKey;
import org.ethereum.crypto.ECKeyBC;
import org.ethereum.crypto.HashUtil;
import org.junit.BeforeClass;
import org.junit.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.math.BigInteger;
import java.security.SignatureException;
import java.util.Arrays;

import static org.junit.Assert.*;

public class NativeTest {

    private static final Logger LOGGER = LoggerFactory.getLogger(NativeTest.class);
    public static final int VERSION_OFFSET = 27;

    private String privString = "3ecb44df2159c26e0f995712d4f39b6f6e499b40749b1cf1246c37f9516cb6a4";
    private BigInteger privateKey = new BigInteger(Hex.decode(privString));

    private String pubString = "0497466f2b32bc3bb76d4741ae51cd1d8578b48d3f1e68da206d47321aec267ce78549b514e4453d74ef11b0cd5e4e4c364effddac8b51bcfc8de80682f952896f";
    private String compressedPubString = "0397466f2b32bc3bb76d4741ae51cd1d8578b48d3f1e68da206d47321aec267ce7";
    private byte[] pubKey = Hex.decode(pubString);
    private byte[] compressedPubKey = Hex.decode(compressedPubString);
    private String address = "8a40bfaa73256b60764c1bf40675a99083efb075";

    private String exampleMessage = "This is an example of a signed message.";

    // Signature components
    private final BigInteger r = new BigInteger("28157690258821599598544026901946453245423343069728565040002908283498585537001");
    private final BigInteger s = new BigInteger("30212485197630673222315826773656074299979444367665131281281249560925428307087");
    byte v = 28;

    @BeforeClass
    public static void setup() {
        LOGGER.debug("Secp256k1Context.isEnabled = {}", org.bitcoin.Secp256k1Context.isEnabled());
    }

    @Test
    public void testPubFromPriv() throws NativeSecp256k1Util.AssertFailException {
        LOGGER.debug("Start testPubFromPriv.");

        byte[] pubFromPrivNative = NativeSecp256k1.computePubkey(Hex.decode(privString));
        LOGGER.debug("NativeSecp256k1.computePubkey = {}", Hex.toHexString(pubFromPrivNative));
        assertArrayEquals(pubKey, pubFromPrivNative);

        byte[] pubFromPrivBC = ECKeyBC.publicKeyFromPrivate(privateKey, false);
        LOGGER.debug("ECKeyBC.publicKeyFromPrivate = {}", Hex.toHexString(pubFromPrivNative));
        assertArrayEquals(pubKey, pubFromPrivBC);
    }

    @Test
    public void testPubFromPrivCompresed() throws NativeSecp256k1Util.AssertFailException {
        LOGGER.debug("Start testPubFromPrivCompresed.");

        byte[] pubFromPrivBC = ECKeyBC.publicKeyFromPrivate(privateKey, true);
        LOGGER.debug("ECKeyBC.publicKeyFromPrivate = {}", Hex.toHexString(pubFromPrivBC));
        assertArrayEquals(compressedPubKey, pubFromPrivBC);

        byte[] pubFromPrivNative = NativeSecp256k1.computePubkey(Hex.decode(privString));
        LOGGER.debug("NativeSecp256k1.computePubkey = {}", Hex.toHexString(pubFromPrivNative));
        assertArrayEquals(compressedPubKey, pubFromPrivNative);
    }

    @Test
    public void testNativeSignCompact() throws NativeSecp256k1Util.AssertFailException, SignatureException {

        byte[] privateKey = Hex.decode(privString);

        for (int i = 0; i < 100; i++) {
            byte[] messageHash = HashUtil.keccak256(exampleMessage.getBytes());
            LOGGER.debug("Signing message: {}, with pK: {}", exampleMessage, privString);
            testSignMessage(messageHash, privateKey);
        }
    }

    void testSignMessage(byte[] messageHash, byte[] privateKey) throws NativeSecp256k1Util.AssertFailException, SignatureException {

        LOGGER.debug("Signing with Bouncy Castle...");
        ECDSASignature signatureRecoverableBC = ECKeyBC.fromPrivate(privateKey).sign(messageHash);
        byte[] signatureRecoverableBCToNative = getNativeBytesSignature(signatureRecoverableBC);
        LOGGER.debug("Signature by BC: [{}] = {}", signatureRecoverableBCToNative.length, signatureRecoverableBCToNative);

        LOGGER.debug("Signing with Native secp256k1...");
        byte[] signatureRecoverableNative = NativeSecp256k1.signRecoverable(messageHash, privateKey);
        byte[] signatureNative = NativeSecp256k1.sign(messageHash, privateKey);
        ECDSASignature signatureRecoverableNativeToBC = getEcdsaSignature(signatureRecoverableNative);
        LOGGER.debug("Signature by Native: [{}] = {}", signatureRecoverableNative.length, signatureRecoverableNative);


        LOGGER.debug("Recovering...");
        byte[] pkRecoveredFromNativeByBC = ECKeyBC.signatureToKey(messageHash, signatureRecoverableNativeToBC).getPubKey();
        String pkRecoveredFromNativeByBCStr = Hex.toHexString(pkRecoveredFromNativeByBC);
        LOGGER.debug("Pub key recovered FromNativeByBC : {}", pkRecoveredFromNativeByBCStr);
        byte[] pkRecoveredFromBCByBC = ECKeyBC.signatureToKey(messageHash, signatureRecoverableBC).getPubKey();
        String pkRecoveredFromBCByBCStr = Hex.toHexString(pkRecoveredFromBCByBC);
        LOGGER.debug("Pub key recovered FromBCByBC : {}", pkRecoveredFromBCByBCStr);
        byte[] pkRecoveredFromBCByNative = NativeSecp256k1.ecdsaRecover(Arrays.copyOf(signatureRecoverableBCToNative, 64), messageHash, signatureRecoverableBCToNative[64]);
        String pkRecoveredFromBCByNativeStr = Hex.toHexString(pkRecoveredFromBCByNative);
        LOGGER.debug("Pub key recovered FromBCByNative : {}", pkRecoveredFromBCByNativeStr);
        byte[] pkRecoveredFromNativeByNative = NativeSecp256k1.ecdsaRecover(Arrays.copyOf(signatureRecoverableNative, 64), messageHash, signatureRecoverableNative[64]);
        String pkRecoveredFromNativeByNativeStr = Hex.toHexString(pkRecoveredFromNativeByNative);
        LOGGER.debug("Pub key recovered FromNativeByNative : {}", pkRecoveredFromNativeByNativeStr);

        assertEquals("", pubString, pkRecoveredFromNativeByBCStr);
        assertEquals("", pubString, pkRecoveredFromBCByBCStr);
        assertEquals("", pubString, pkRecoveredFromBCByNativeStr);
        assertEquals("", pubString, pkRecoveredFromNativeByNativeStr);

        assertEquals("Address fail", address, Hex.toHexString(this.getAddress(pkRecoveredFromNativeByBC)));
        assertEquals("Address fail", address, Hex.toHexString(this.getAddress(pkRecoveredFromBCByBC)));
        assertEquals("Address fail", address, Hex.toHexString(this.getAddress(pkRecoveredFromBCByNative)));
        assertEquals("Address fail", address, Hex.toHexString(this.getAddress(pkRecoveredFromNativeByNative)));

        LOGGER.debug("Verifying with BC...");
        ECKeyBC key = ECKeyBC.fromPublicOnly(pubKey);
        assertTrue("Couldnt verify", key.verify(messageHash, signatureRecoverableBC, pubKey));
        assertTrue("Couldnt verify", key.verify(messageHash, signatureRecoverableNativeToBC, pubKey));


        LOGGER.debug("Verifying with native...");
        assertTrue("Couldnt verify", NativeSecp256k1.verify(messageHash, signatureNative, pubKey));
        assertTrue("Couldnt verify", NativeSecp256k1.verify(messageHash, Arrays.copyOf(signatureRecoverableBCToNative, 64), pkRecoveredFromBCByNative));
        assertTrue("Couldnt verify", NativeSecp256k1.verify(messageHash, Arrays.copyOf(signatureRecoverableNative, 64), pkRecoveredFromNativeByBC));
        assertTrue("Couldnt verify", NativeSecp256k1.verify(messageHash, Arrays.copyOf(signatureRecoverableNative, 64), pkRecoveredFromNativeByNative));


    }

    ECDSASignature getEcdsaSignature(byte[] signCompact) {
        byte[] r = Arrays.copyOfRange(signCompact, 0, 32);
        byte[] s = Arrays.copyOfRange(signCompact, 32, 64);
        byte v = (byte) (signCompact[64] + 27);
        return ECDSASignature.fromComponents(r, s, v);
    }

    private byte[] getNativeBytesSignature(ECDSASignature signature) {
        byte[] result = new byte[65];
        byte[] rBytes = signature.r.toByteArray();
        byte[] sBytes = signature.s.toByteArray();
        System.arraycopy(rBytes, rBytes.length - 32, result, 0, 32);
        System.arraycopy(sBytes, sBytes.length - 32, result, 32, 32);
        result[64] = (byte) (signature.v - VERSION_OFFSET);
        return result;
    }

    public byte[] getAddress(byte[] pub) {
        return ECKeyBC.fromPublicOnly(pub).getAddress();
    }

}
