package org.simplejavamail.internal.clisupport.serialization;

import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.Arrays;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class CliMetadataCacheTest {
	private final byte[] fingerprint = new byte[32];
	private final byte[] payload = "generated metadata".getBytes(StandardCharsets.UTF_8);

	@Test
	void versionedEnvelopeRoundTrips() {
		final byte[] encoded = CliMetadataCache.wrap(CliMetadataCache.Kind.CLI_OPTIONS, fingerprint, payload);

		assertThat(CliMetadataCache.unwrap(CliMetadataCache.Kind.CLI_OPTIONS, fingerprint, encoded))
				.containsExactly(payload);
	}

	@Test
	void corruptionTruncationVersionKindAndFingerprintMismatchFailBeforeDeserialization() {
		final byte[] encoded = CliMetadataCache.wrap(CliMetadataCache.Kind.CLI_OPTIONS, fingerprint, payload);

		final byte[] corrupt = encoded.clone();
		corrupt[corrupt.length - 1] ^= 1;
		assertThatThrownBy(() -> CliMetadataCache.unwrap(CliMetadataCache.Kind.CLI_OPTIONS, fingerprint, corrupt))
				.hasMessageContaining("checksum");

		assertThatThrownBy(() -> CliMetadataCache.unwrap(CliMetadataCache.Kind.CLI_OPTIONS, fingerprint,
				Arrays.copyOf(encoded, encoded.length - 1))).hasMessageContaining("payload length");

		final byte[] wrongVersion = encoded.clone();
		wrongVersion[4] = 0;
		wrongVersion[5] = 2;
		assertThatThrownBy(() -> CliMetadataCache.unwrap(CliMetadataCache.Kind.CLI_OPTIONS, fingerprint, wrongVersion))
				.hasMessageContaining("version 2");

		assertThatThrownBy(() -> CliMetadataCache.unwrap(CliMetadataCache.Kind.THERAPI_JAVADOC, fingerprint, encoded))
				.hasMessageContaining("kind");

		final byte[] otherFingerprint = fingerprint.clone();
		otherFingerprint[0] = 1;
		assertThatThrownBy(() -> CliMetadataCache.unwrap(CliMetadataCache.Kind.CLI_OPTIONS, otherFingerprint, encoded))
				.hasMessageContaining("fingerprint");
	}
}
