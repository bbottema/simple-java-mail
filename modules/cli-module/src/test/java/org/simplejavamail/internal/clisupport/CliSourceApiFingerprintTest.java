package org.simplejavamail.internal.clisupport;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class CliSourceApiFingerprintTest {
	@Test
	void sourceApiFingerprintIsDeterministicAndSha256Sized() {
		final byte[] first = CliSourceApiFingerprint.calculate();
		final byte[] second = CliSourceApiFingerprint.calculate();

		assertThat(first).hasSize(32).containsExactly(second);
	}
}
