package org.simplejavamail.api.mailer.config;

import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.ObjectInputStream;
import java.io.ObjectOutputStream;
import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class FailoverPolicyTest {

	@Test
	void defaultsAndResetsAgreeAndTheBuilderDoesNotMutateBuiltPolicies() throws Exception {
		final FailoverPolicy defaults = FailoverPolicy.builder().build();
		assertThat(defaults.getPrimaryConnectionAttempts()).isEqualTo(1);
		assertThat(defaults.getPrimaryConnectionRetryDelay()).isZero();
		assertThat(defaults.getPrimaryRecheckInterval()).isEqualTo(Duration.ofSeconds(30));
		assertThat(defaults.getPrimaryMaximumRecheckInterval()).isEqualTo(Duration.ofSeconds(30));
		assertThat(defaults.getPrimaryRecheckBackoffMultiplier()).isEqualTo(1);
		assertThat(defaults.isFailoverOnAuthenticationFailure()).isFalse();
		assertThat(defaults.isFailoverOnCertificateVerificationFailure()).isFalse();
		final FailoverPolicy.Builder builder = FailoverPolicy.builder().withPrimaryConnectionAttempts(3)
				.withPrimaryConnectionRetryDelay(Duration.ofMillis(50)).withPrimaryRecheckInterval(Duration.ofSeconds(1))
				.withPrimaryRecheckBackoff(2, Duration.ofMinutes(1)).withFailoverOnAuthenticationFailure(true)
				.withFailoverOnCertificateVerificationFailure(true);
		final FailoverPolicy configured = builder.build();
		assertThat(builder.resetPrimaryConnectionAttempts().resetPrimaryConnectionRetryDelay().resetPrimaryRecheckInterval()
				.resetPrimaryRecheckBackoff().resetFailoverOnAuthenticationFailure().resetFailoverOnCertificateVerificationFailure().build())
				.isEqualTo(defaults);
		assertThat(configured.getPrimaryConnectionAttempts()).isEqualTo(3);
		final ByteArrayOutputStream bytes = new ByteArrayOutputStream();
		try (ObjectOutputStream output = new ObjectOutputStream(bytes)) { output.writeObject(configured); }
		try (ObjectInputStream input = new ObjectInputStream(new ByteArrayInputStream(bytes.toByteArray()))) {
			assertThat(input.readObject()).isEqualTo(configured);
		}
	}

	@Test
	void validatesCombinedSuppliedValuesAndRepresentableDurations() {
		assertThatThrownBy(() -> FailoverPolicy.builder().withPrimaryConnectionAttempts(0).build()).isInstanceOf(IllegalArgumentException.class);
		assertThatThrownBy(() -> FailoverPolicy.builder().withPrimaryConnectionRetryDelay(Duration.ofNanos(-1)).build())
				.isInstanceOf(IllegalArgumentException.class);
		assertThatThrownBy(() -> FailoverPolicy.builder().withPrimaryRecheckInterval(Duration.ZERO).build()).isInstanceOf(IllegalArgumentException.class);
		assertThatThrownBy(() -> FailoverPolicy.builder().withPrimaryRecheckInterval(Duration.ofSeconds(Long.MAX_VALUE)).build())
				.isInstanceOf(IllegalArgumentException.class).hasMessageContaining("too large");
		assertThatThrownBy(() -> FailoverPolicy.builder().withPrimaryRecheckBackoff(1, Duration.ofSeconds(30)))
				.isInstanceOf(IllegalArgumentException.class);
		assertThatThrownBy(() -> FailoverPolicy.builder().withPrimaryRecheckBackoff(2, Duration.ofSeconds(1)).build())
				.isInstanceOf(IllegalArgumentException.class);
		assertThatThrownBy(() -> new FailoverPolicy(1, Duration.ZERO, Duration.ofSeconds(30), 0, Duration.ofSeconds(30), false, false))
				.isInstanceOf(IllegalArgumentException.class);
	}
}
