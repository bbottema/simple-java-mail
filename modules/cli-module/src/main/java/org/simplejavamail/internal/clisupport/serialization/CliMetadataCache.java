package org.simplejavamail.internal.clisupport.serialization;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.EOFException;
import java.io.IOException;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;

/**
 * Protects generated CLI startup caches from stale APIs, wrong-file use, truncation, and accidental corruption.
 * The deterministic envelope records its kind, format version, source-API fingerprint, payload length, and SHA-256
 * checksum before Kryo or Therapi data is consumed. It is an integrity boundary for trusted build artifacts, not a
 * signature scheme for accepting untrusted metadata.
 */
public final class CliMetadataCache {
	private static final int MAGIC = 0x534a4d43;
	private static final short FORMAT_VERSION = 1;
	private static final int DIGEST_BYTES = 32;
	private static final int MAX_PAYLOAD_BYTES = 8 * 1024 * 1024;

	/** Separates caches whose payloads happen to share the same binary serializer. */
	public enum Kind {
		CLI_OPTIONS(1),
		THERAPI_JAVADOC(2);

		private final int code;

		Kind(final int code) {
			this.code = code;
		}
	}

	private CliMetadataCache() {
	}

	/** Wraps a trusted generated payload in the deterministic validation envelope. */
	public static byte[] wrap(final Kind kind, final byte[] sourceApiFingerprint, final byte[] payload) {
		validateFingerprint(sourceApiFingerprint);
		if (payload.length > MAX_PAYLOAD_BYTES) {
			throw new IllegalArgumentException("CLI metadata payload exceeds its size limit");
		}
		try {
			final ByteArrayOutputStream bytes = new ByteArrayOutputStream();
			try (DataOutputStream output = new DataOutputStream(bytes)) {
				output.writeInt(MAGIC);
				output.writeShort(FORMAT_VERSION);
				output.writeByte(kind.code);
				output.write(sourceApiFingerprint);
				output.writeInt(payload.length);
				output.write(sha256(payload));
				output.write(payload);
			}
			return bytes.toByteArray();
		} catch (IOException e) {
			throw new IllegalStateException("Unable to encode CLI metadata", e);
		}
	}

	/** Rejects an invalid envelope before returning the trusted generated payload to its decoder. */
	public static byte[] unwrap(final Kind expectedKind, final byte[] expectedSourceApiFingerprint,
			final byte[] encoded) {
		validateFingerprint(expectedSourceApiFingerprint);
		try (DataInputStream input = new DataInputStream(new ByteArrayInputStream(encoded))) {
			if (input.readInt() != MAGIC) {
				throw invalidMetadata("CLI metadata has an invalid or legacy format header");
			}
			final short version = input.readShort();
			if (version != FORMAT_VERSION) {
				throw invalidMetadata("CLI metadata format version " + version + " is unsupported");
			}
			if (input.readUnsignedByte() != expectedKind.code) {
				throw invalidMetadata("CLI metadata cache kind does not match its file");
			}
			final byte[] fingerprint = input.readNBytes(DIGEST_BYTES);
			if (fingerprint.length != DIGEST_BYTES
					|| !MessageDigest.isEqual(fingerprint, expectedSourceApiFingerprint)) {
				throw invalidMetadata("CLI metadata source API fingerprint does not match");
			}
			final int payloadLength = input.readInt();
			if (payloadLength < 0 || payloadLength > MAX_PAYLOAD_BYTES
					|| payloadLength > input.available() - DIGEST_BYTES) {
				throw invalidMetadata("CLI metadata payload length is invalid");
			}
			final byte[] expectedPayloadDigest = input.readNBytes(DIGEST_BYTES);
			final byte[] payload = input.readNBytes(payloadLength);
			if (expectedPayloadDigest.length != DIGEST_BYTES || payload.length != payloadLength || input.read() != -1) {
				throw invalidMetadata("CLI metadata is truncated or has trailing data");
			}
			if (!MessageDigest.isEqual(expectedPayloadDigest, sha256(payload))) {
				throw invalidMetadata("CLI metadata payload checksum does not match");
			}
			return payload;
		} catch (EOFException e) {
			throw invalidMetadata("CLI metadata is truncated", e);
		} catch (IOException e) {
			throw invalidMetadata("Unable to read CLI metadata", e);
		}
	}

	private static void validateFingerprint(final byte[] fingerprint) {
		if (fingerprint.length != DIGEST_BYTES) {
			throw new IllegalArgumentException("CLI source API fingerprint must contain 32 bytes");
		}
	}

	private static byte[] sha256(final byte[] value) {
		try {
			return MessageDigest.getInstance("SHA-256").digest(value);
		} catch (GeneralSecurityException e) {
			throw new IllegalStateException("SHA-256 unavailable", e);
		}
	}

	private static IllegalStateException invalidMetadata(final String message) {
		return new IllegalStateException(message);
	}

	private static IllegalStateException invalidMetadata(final String message, final Exception cause) {
		return new IllegalStateException(message, cause);
	}
}
