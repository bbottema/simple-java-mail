package org.simplejavamail.internal.email;

import org.jetbrains.annotations.ApiStatus;

import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.math.BigInteger;
import java.security.GeneralSecurityException;
import java.security.KeyFactory;
import java.security.MessageDigest;
import java.security.PublicKey;
import java.security.interfaces.DSAPublicKey;
import java.security.interfaces.ECPublicKey;
import java.security.interfaces.RSAPublicKey;
import java.security.spec.ECField;
import java.security.spec.ECFieldF2m;
import java.security.spec.ECFieldFp;
import java.security.spec.ECParameterSpec;
import java.security.spec.ECPoint;
import java.security.spec.X509EncodedKeySpec;
import java.util.Locale;
import java.util.Objects;

/** One canonical signing-key identity boundary shared by approval setup and actual signature capture. */
@ApiStatus.Internal
public final class SigningKeyFingerprint {
    private SigningKeyFingerprint() { }

    /** Snapshot public mathematical components where JCA exposes them, independently of certificate and provider encodings. */
    public static String forSmime(final PublicKey supplied) {
        final PublicKey key = canonicalKey(Objects.requireNonNull(supplied, "publicKey"));
        try {
            final ByteArrayOutputStream bytes = new ByteArrayOutputStream();
            try (DataOutputStream content = new DataOutputStream(bytes)) {
                writePublicKey(content, key);
            }
            return "SHA-256:" + hexadecimal(MessageDigest.getInstance("SHA-256").digest(bytes.toByteArray()));
        } catch (GeneralSecurityException failure) {
            throw new IllegalStateException("SHA-256 is unavailable", failure);
        } catch (IOException impossible) {
            throw new IllegalStateException("Could not encode an in-memory public key", impossible);
        }
    }

    /** Normalize spelling and require a full v4/v5/v6 signing-key fingerprint, never a short key ID. */
    public static String forOpenPgp(final String supplied) {
        final String fingerprint = normalizeOpenPgp(supplied);
        if (!fingerprint.matches("(?:[0-9A-F]{40}|[0-9A-F]{64})")) {
            throw new IllegalArgumentException("OpenPGP approval requires a full 40- or 64-hex signing-key fingerprint");
        }
        return fingerprint;
    }

    /** Passive builder collection can retain unfinished text until its documented build boundary. */
    public static String normalizeOpenPgp(final String supplied) {
        return Objects.requireNonNull(supplied, "fingerprint").replaceAll("[\\s:]", "").toUpperCase(Locale.ROOT);
    }

    private static PublicKey canonicalKey(final PublicKey supplied) {
        if (supplied instanceof RSAPublicKey || supplied instanceof DSAPublicKey || supplied instanceof ECPublicKey) return supplied;
        final byte[] encoded = supplied.getEncoded();
        if (encoded == null) throw new IllegalArgumentException("The public signing key has no usable public encoding");
        try {
            return KeyFactory.getInstance(supplied.getAlgorithm()).generatePublic(new X509EncodedKeySpec(encoded));
        } catch (GeneralSecurityException unsupported) {
            return supplied;
        }
    }

    private static void writePublicKey(final DataOutputStream content, final PublicKey key) throws IOException {
        if (key instanceof RSAPublicKey) {
            final RSAPublicKey rsa = (RSAPublicKey) key;
            content.writeUTF("RSA");
            integer(content, rsa.getModulus());
            integer(content, rsa.getPublicExponent());
        } else if (key instanceof DSAPublicKey) {
            final DSAPublicKey dsa = (DSAPublicKey) key;
            content.writeUTF("DSA");
            integer(content, dsa.getY());
            integer(content, dsa.getParams().getP());
            integer(content, dsa.getParams().getQ());
            integer(content, dsa.getParams().getG());
        } else if (key instanceof ECPublicKey) {
            final ECPublicKey ec = (ECPublicKey) key;
            content.writeUTF("EC");
            curve(content, ec.getParams());
            point(content, ec.getW());
        } else {
            content.writeUTF(key.getAlgorithm().toUpperCase(Locale.ROOT));
            final byte[] encoded = Objects.requireNonNull(key.getEncoded(), "public key encoding");
            content.writeInt(encoded.length);
            content.write(encoded);
        }
    }

    private static void curve(final DataOutputStream content, final ECParameterSpec parameters) throws IOException {
        final ECField field = parameters.getCurve().getField();
        if (field instanceof ECFieldFp) {
            content.writeUTF("Fp");
            integer(content, ((ECFieldFp) field).getP());
        } else if (field instanceof ECFieldF2m) {
            final ECFieldF2m binary = (ECFieldF2m) field;
            content.writeUTF("F2m");
            content.writeInt(binary.getM());
            final int[] terms = binary.getMidTermsOfReductionPolynomial();
            content.writeInt(terms == null ? 0 : terms.length);
            if (terms != null) for (int term : terms) content.writeInt(term);
        } else {
            throw new IllegalArgumentException("Unsupported elliptic-curve field for signing-key approval");
        }
        integer(content, parameters.getCurve().getA());
        integer(content, parameters.getCurve().getB());
        point(content, parameters.getGenerator());
        integer(content, parameters.getOrder());
        content.writeInt(parameters.getCofactor());
    }

    private static void point(final DataOutputStream content, final ECPoint point) throws IOException {
        integer(content, point.getAffineX());
        integer(content, point.getAffineY());
    }

    private static void integer(final DataOutputStream content, final BigInteger number) throws IOException {
        final byte[] bytes = Objects.requireNonNull(number, "public key component").toByteArray();
        content.writeInt(bytes.length);
        content.write(bytes);
    }

    private static String hexadecimal(final byte[] bytes) {
        final char[] digits = "0123456789ABCDEF".toCharArray();
        final char[] result = new char[bytes.length * 2];
        for (int index = 0; index < bytes.length; index++) {
            result[index * 2] = digits[(bytes[index] & 255) >>> 4];
            result[index * 2 + 1] = digits[bytes[index] & 15];
        }
        return new String(result);
    }
}
