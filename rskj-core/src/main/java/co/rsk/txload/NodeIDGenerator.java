package co.rsk.txload;

import co.rsk.net.NodeID;
import org.bouncycastle.util.encoders.Hex;
import org.ethereum.crypto.ECKey;

public class NodeIDGenerator {
    public static void main(String[] args) {
        String basePrivKey = "AFF6A83FEFFF6FF0C9F6FFFE41F6FF10D9FFFF3F41FFCFBF41F6FF90DFFFFF91";
        byte[] baseBytes = Hex.decode(basePrivKey);

        for (int i = 0x91; i <= 0x99; i++) {
            baseBytes[baseBytes.length - 1] = (byte) i;
            String privKeyHex = Hex.toHexString(baseBytes).toUpperCase();
            ECKey key = ECKey.fromPrivate(baseBytes);
            byte[] pubKey = key.getNodeId();
            NodeID nodeId = new NodeID(pubKey);

            System.out.println("PrivateKey: " + privKeyHex);
            System.out.println("NodeID:     " + nodeId.toString());
            System.out.println();
        }
    }
}