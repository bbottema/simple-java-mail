package org.simplejavamail.api.mailer.config;

import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.ObjectInputStream;
import java.io.ObjectOutputStream;
import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class SendingRateLimitTest {

	@Test
	void validatesCountsAndRepresentablePeriodsWithoutDefaults() {
		assertThatThrownBy(() -> new SendingRateLimit(0, Duration.ofMinutes(1))).isInstanceOf(IllegalArgumentException.class);
		assertThatThrownBy(() -> new SendingRateLimit(-1, Duration.ofMinutes(1))).isInstanceOf(IllegalArgumentException.class);
		assertThatThrownBy(() -> new SendingRateLimit(1, Duration.ZERO)).isInstanceOf(IllegalArgumentException.class);
		assertThatThrownBy(() -> new SendingRateLimit(1, Duration.ofNanos(-1))).isInstanceOf(IllegalArgumentException.class);
		assertThatThrownBy(() -> new SendingRateLimit(1, Duration.ofSeconds(Long.MAX_VALUE))).isInstanceOf(IllegalArgumentException.class);
		assertThatThrownBy(() -> new SendingRateLimit(1, null)).isInstanceOf(NullPointerException.class);
		assertThat(new SendingRateLimit(Integer.MAX_VALUE, Duration.ofNanos(Long.MAX_VALUE)).getCount()).isEqualTo(Integer.MAX_VALUE);
	}

	@Test
	void keepsValueEqualityAndSerialization() throws Exception {
		final SendingRateLimit original = new SendingRateLimit(30, Duration.ofMinutes(1));
		final ByteArrayOutputStream bytes = new ByteArrayOutputStream();
		try (ObjectOutputStream output = new ObjectOutputStream(bytes)) {
			output.writeObject(original);
		}
		try (ObjectInputStream input = new ObjectInputStream(new ByteArrayInputStream(bytes.toByteArray()))) {
			final SendingRateLimit restored = (SendingRateLimit) input.readObject();
			assertThat(restored).isEqualTo(original).hasSameHashCodeAs(original);
			assertThat(restored.getPeriod()).isEqualTo(Duration.ofMinutes(1));
		}
	}
}
