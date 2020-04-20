package org.ethereum.crypto;


import org.bouncycastle.math.ec.ECPoint;
import org.ethereum.config.Constants;

import java.math.BigInteger;

import static org.ethereum.util.BIUtil.isLessThan;

/**
 * Groups the two components that make up a signature, and provides a way to encode to Base64 form, which is
 * how ECDSA signatures are represented when embedded in other data structures in the Ethereum protocol. The raw
 * components can be useful for doing further EC maths on them.
 */
public class ECDSASignature {
    /**
     * The two components of the signature.
     */
    public final BigInteger r;
    public final BigInteger s;
    public byte v;

    /**
     * Constructs a signature with the given components. Does NOT automatically canonicalise the signature.
     *
     * @param r -
     * @param s -
     */
    public ECDSASignature(BigInteger r, BigInteger s) {
        this.r = r;
        this.s = s;
    }

    /**
     *t
     * @param r
     * @param s
     * @return -
     */
    private static ECDSASignature fromComponents(byte[] r, byte[] s) {
        return new ECDSASignature(new BigInteger(1, r), new BigInteger(1, s));
    }

    /**
     *
     * @param r -
     * @param s -
     * @param v -
     * @return -
     */
    public static ECDSASignature fromComponents(byte[] r, byte[] s, byte v) {
        ECDSASignature signature = fromComponents(r, s);
        signature.v = v;
        return signature;
    }

    /**
     *
     * @param r -
     * @param s -
     * @param hash - the hash used to compute this signature
     * @param pub - public key bytes, used to calculate the recovery byte 'v'
     * @return -
     */
    public static ECDSASignature fromComponentsWithRecoveryCalculation(byte[] r, byte[] s, byte[] hash, byte[] pub) {
        byte v = calculateRecoveryByte(r, s, hash, pub);
        return fromComponents(r, s, v);
    }

    private static byte calculateRecoveryByte(byte[] r, byte[] s, byte[] hash, byte[] pub) {
        ECDSASignature sig = ECDSASignature.fromComponents(r, s);
        ECPoint pubPoint = ECKeyBC.fromPublicOnly(pub).pub;

        // Now we have to work backwards to figure out the recId needed to recover the signature.
        int recId = -1;
        for (int i = 0; i < 4; i++) {
            ECKeyBC k = ECKeyBC.recoverFromSignature(i, sig, hash, false);
            if (k != null && k.pub.equals(pubPoint)) {
                recId = i;
                break;
            }
        }

        if (recId == -1) {
            throw new RuntimeException("Could not construct a recoverable key. This should never happen.");
        }

        return (byte) (recId + 27);
    }

    public boolean validateComponents() {
        return validateComponents(r, s, v);
    }

    public static boolean validateComponents(BigInteger r, BigInteger s, byte v) {

        if (v != 27 && v != 28) {
            return false;
        }

        if (isLessThan(r, BigInteger.ONE)) {
            return false;
        }

        if (isLessThan(s, BigInteger.ONE)) {
            return false;
        }

        if (!isLessThan(r, Constants.getSECP256K1N())) {
            return false;
        }

        if (!isLessThan(s, Constants.getSECP256K1N())) {
            return false;
        }

        return true;
    }

    /**
     * Will automatically adjust the S component to be less than or equal to half the curve order, if necessary.
     * This is required because for every signature (r,s) the signature (r, -s (mod N)) is a valid signature of
     * the same message. However, we dislike the ability to modify the bits of a Ethereum transaction after it's
     * been signed, as that violates various assumed invariants. Thus in future only one of those forms will be
     * considered legal and the other will be banned.
     *
     * @return  -
     */
    public ECDSASignature toCanonicalised() {
        if (s.compareTo(ECKeyBC.HALF_CURVE_ORDER) > 0) {
            // The order of the curve is the number of valid points that exist on that curve. If S is in the upper
            // half of the number of valid points, then bring it back to the lower half. Otherwise, imagine that
            //    N = 10
            //    s = 8, so (-8 % 10 == 2) thus both (r, 8) and (r, 2) are valid solutions.
            //    10 - 8 == 2, giving us always the latter solution, which is canonical.
            return new ECDSASignature(r, ECKeyBC.CURVE.getN().subtract(s));
        } else {
            return this;
        }
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) {
            return true;
        }

        if (o == null || getClass() != o.getClass()) {
            return false;
        }

        ECDSASignature signature = (ECDSASignature) o;

        if (!r.equals(signature.r)) {
            return false;
        }

        if (!s.equals(signature.s)) {
            return false;
        }

        return true;
    }

    @Override
    public int hashCode() {
        int result = r.hashCode();
        result = 31 * result + s.hashCode();
        return result;
    }
}
