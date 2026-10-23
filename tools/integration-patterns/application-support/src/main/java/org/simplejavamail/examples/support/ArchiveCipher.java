package org.simplejavamail.examples.support;

import javax.crypto.Cipher;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import java.nio.ByteBuffer;
import java.security.SecureRandom;
import java.util.Base64;

/**
 * Keep archived demonstration messages encrypted at rest, separately from their email-level S/MIME protection.
 * The default key is PUBLIC demo data. A real application must supply its own key management and rotation scheme.
 */
public final class ArchiveCipher {
    private static final int NONCE_BYTES = 12;
    private static final int AUTHENTICATION_TAG_BITS = 128;
    private final byte[] key = Base64.getDecoder().decode(ExampleHttp.environment("ARCHIVE_KEY_BASE64",
            "MDEyMzQ1Njc4OWFiY2RlZjAxMjM0NTY3ODlhYmNkZWY="));
    private final SecureRandom random = new SecureRandom();

    public byte[] encrypt(byte[] plaintext) {
        byte[] nonce = new byte[NONCE_BYTES];
        random.nextBytes(nonce);
        byte[] ciphertext = transform(Cipher.ENCRYPT_MODE, nonce, plaintext);
        // The random nonce is not a secret. Store it before the authenticated ciphertext so the archive can be read later.
        return ByteBuffer.allocate(nonce.length + ciphertext.length).put(nonce).put(ciphertext).array();
    }

    public byte[] decrypt(byte[] stored) {
        if (stored.length < NONCE_BYTES + AUTHENTICATION_TAG_BITS / Byte.SIZE) {
            throw new IllegalArgumentException("The archive object is incomplete");
        }
        return transform(Cipher.DECRYPT_MODE, java.util.Arrays.copyOf(stored, NONCE_BYTES),
                java.util.Arrays.copyOfRange(stored, NONCE_BYTES, stored.length));
    }

    private byte[] transform(int mode, byte[] nonce, byte[] input) {
        try {
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(mode, new SecretKeySpec(key, "AES"), new GCMParameterSpec(AUTHENTICATION_TAG_BITS, nonce));
            return cipher.doFinal(input);
        } catch (java.security.GeneralSecurityException failed) {
            throw new IllegalStateException("Archive protection failed; do not substitute an unprotected copy", failed);
        }
    }
}
