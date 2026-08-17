package org.simplejavamail.internal.util;

import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.Base64;

import static org.assertj.core.api.Assertions.assertThat;

class ProviderNeutralStreamProviderTest {

	private final ProviderNeutralStreamProvider provider = new ProviderNeutralStreamProvider();

	@Test
	void base64FlushFinalizesTheLastPartialQuantumWithoutClosingTheDestination() throws Exception {
		final byte[] original = "signature bytes whose length is not divisible by three!".getBytes(StandardCharsets.UTF_8);
		final ByteArrayOutputStream destination = new ByteArrayOutputStream();
		final OutputStream encoded = provider.outputBase64(destination);

		encoded.write(original);
		encoded.flush();
		destination.write('\n');

		final String wire = new String(destination.toByteArray(), StandardCharsets.US_ASCII).trim();
		assertThat(Base64.getMimeDecoder().decode(wire)).containsExactly(original);
		assertThat(wire).endsWith("=");
	}

	@Test
	void encodedWordBase64AlsoFinalizesOnFlush() throws Exception {
		final byte[] original = "header".getBytes(StandardCharsets.UTF_8);
		final ByteArrayOutputStream destination = new ByteArrayOutputStream();
		final OutputStream encoded = provider.outputB(destination);

		encoded.write(original);
		encoded.flush();

		assertThat(Base64.getDecoder().decode(destination.toByteArray())).containsExactly(original);
	}

	@Test
	void quotedPrintableFlushEncodesTrailingWhitespaceWithoutClosingTheDestination() throws Exception {
		final ByteArrayOutputStream destination = new ByteArrayOutputStream();
		final OutputStream encoded = provider.outputQP(destination);

		encoded.write("body with trailing space ".getBytes(StandardCharsets.US_ASCII));
		encoded.flush();
		destination.write('!');

		assertThat(new String(destination.toByteArray(), StandardCharsets.US_ASCII))
				.isEqualTo("body with trailing space=20!");
	}
}
