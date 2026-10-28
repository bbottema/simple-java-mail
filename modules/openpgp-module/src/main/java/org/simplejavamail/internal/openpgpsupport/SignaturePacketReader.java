package org.simplejavamail.internal.openpgpsupport;

import org.bouncycastle.openpgp.PGPMarker;
import org.bouncycastle.openpgp.PGPCompressedData;
import org.bouncycastle.openpgp.PGPSignature;
import org.bouncycastle.openpgp.PGPSignatureList;
import org.bouncycastle.openpgp.PGPObjectFactory;
import org.bouncycastle.openpgp.api.OpenPGPImplementation;
import org.pgpainless.util.ArmorUtils;

import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.ArrayList;
import java.util.List;

/** Native packet parsing with an explicit end state; a stopped inspection never claims complete verification. */
final class SignaturePacketReader {
    private static final int MAX_PACKET_OBJECTS = 10_000;

    static Packets read(final byte[] encoded) {
        final Packets result = new Packets();
        try (InputStream raw = new ByteArrayInputStream(encoded);
             InputStream decoded = ArmorUtils.getDecoderStream(raw)) {
            readObjects(decoded, result);
        } catch (Exception failure) {
            result.fail("Unable to read the OpenPGP signature packets");
        }
        if (result.signatures.isEmpty()) result.fail("No OpenPGP signature packet was available");
        return result;
    }

    private static void readObjects(final InputStream source, final Packets result) throws Exception {
        final PGPObjectFactory packets = OpenPGPImplementation.getInstance().pgpObjectFactory(source);
        Object packet;
        while ((packet = packets.nextObject()) != null) {
            if (++result.objects > MAX_PACKET_OBJECTS) { result.fail("OpenPGP signature packet inspection limit exceeded"); return; }
            if (packet instanceof PGPSignatureList) {
                final PGPSignatureList signatures = (PGPSignatureList) packet;
                for (int i = 0; i < signatures.size(); i++) result.signatures.add(signatures.get(i));
            } else if (packet instanceof PGPCompressedData) {
                result.fail("Compressed OpenPGP detached-signature content was not inspected");
                // Preserve the native helper's no-decompression boundary while retaining later outer signature packets.
                ((PGPCompressedData) packet).getInputStream().transferTo(OutputStream.nullOutputStream());
            } else if (!(packet instanceof PGPMarker)) {
                result.fail("Unexpected packet in the OpenPGP detached-signature payload");
                return;
            }
        }
    }

    static final class Packets {
        final List<PGPSignature> signatures = new ArrayList<>();
        boolean complete = true;
        String failureReason;
        private int objects;
        private void fail(final String reason) { complete = false; if (failureReason == null) failureReason = reason; }
    }
}
