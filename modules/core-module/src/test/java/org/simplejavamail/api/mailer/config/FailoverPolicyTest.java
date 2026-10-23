package org.simplejavamail.api.mailer.config;

import org.junit.jupiter.api.Test;
import org.simplejavamail.api.mailer.MailerGenericBuilder;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.ObjectInputStream;
import java.io.ObjectOutputStream;
import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatNullPointerException;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class FailoverPolicyTest {

	@Test
	void rejectedNullBackoffKeepsThePreviousScheduleAndOtherChoices() {
		final FailoverPolicy.Builder builder = FailoverPolicy.builder().withPrimaryConnectionAttempts(3)
				.withPrimaryConnectionRetryDelay(Duration.ofMillis(50)).withPrimaryRecheckInterval(Duration.ofSeconds(2))
				.withPrimaryRecheckBackoff(2, Duration.ofMinutes(2)).withFailoverOnAuthenticationFailure(true)
				.withFailoverOnCertificateVerificationFailure(true);
		final FailoverPolicy original = builder.build();

		assertThatNullPointerException().isThrownBy(() -> builder.withPrimaryRecheckBackoff(10, null))
				.withMessage("primaryMaximumRecheckInterval");

		assertThat(builder.getPrimaryRecheckBackoffMultiplier()).isEqualTo(2);
		assertThat(builder.getPrimaryMaximumRecheckInterval()).isEqualTo(Duration.ofMinutes(2));
		assertThat(builder.build()).isEqualTo(original);
		assertThatThrownBy(() -> builder.withPrimaryRecheckBackoff(1, Duration.ofMinutes(10))).isInstanceOf(IllegalArgumentException.class);
		assertThat(builder.build()).isEqualTo(original);
		final FailoverPolicy replacement = builder.withPrimaryRecheckBackoff(3, Duration.ofMinutes(3)).build();
		assertThat(replacement.getPrimaryRecheckBackoffMultiplier()).isEqualTo(3);
		assertThat(replacement.getPrimaryMaximumRecheckInterval()).isEqualTo(Duration.ofMinutes(3));
		assertThat(original.getPrimaryRecheckBackoffMultiplier()).isEqualTo(2);
		assertThat(original.getPrimaryMaximumRecheckInterval()).isEqualTo(Duration.ofMinutes(2));
	}

	@Test
	void rejectedNullBackoffKeepsTheDefaultAndResetCapsAbsentUntilBuilding() {
		for (FailoverPolicy.Builder builder : new FailoverPolicy.Builder[]{
				FailoverPolicy.builder(), FailoverPolicy.builder().resetPrimaryRecheckBackoff()}) {
			final FailoverPolicy original = builder.build();

			assertThatNullPointerException().isThrownBy(() -> builder.withPrimaryRecheckBackoff(10, null));

			assertThat(builder.getPrimaryRecheckBackoffMultiplier()).isEqualTo(original.getPrimaryRecheckBackoffMultiplier());
			assertThat(builder.getPrimaryMaximumRecheckInterval()).isNull();
			assertThat(builder.build()).isEqualTo(original);
		}
	}

	@Test
	void policyDefaultsUseThePublishedMailerConstants() {
		final FailoverPolicy policy = FailoverPolicy.builder().build();
		assertThat(policy.getPrimaryConnectionAttempts()).isEqualTo(MailerGenericBuilder.DEFAULT_FAILOVER_PRIMARY_CONNECTION_ATTEMPTS);
		assertThat(policy.getPrimaryConnectionRetryDelay()).isEqualTo(MailerGenericBuilder.DEFAULT_FAILOVER_PRIMARY_CONNECTION_RETRY_DELAY);
		assertThat(policy.getPrimaryRecheckInterval()).isEqualTo(MailerGenericBuilder.DEFAULT_FAILOVER_PRIMARY_RECHECK_INTERVAL);
		assertThat(policy.getPrimaryRecheckBackoffMultiplier()).isEqualTo(MailerGenericBuilder.DEFAULT_FAILOVER_PRIMARY_RECHECK_BACKOFF_MULTIPLIER);
		assertThat(policy.isFailoverOnAuthenticationFailure()).isEqualTo(MailerGenericBuilder.DEFAULT_FAILOVER_ON_AUTHENTICATION_FAILURE);
		assertThat(policy.isFailoverOnCertificateVerificationFailure()).isEqualTo(MailerGenericBuilder.DEFAULT_FAILOVER_ON_CERTIFICATE_VERIFICATION_FAILURE);
	}

	@Test
	void fixedMaximumFollowsTheConfiguredInitialIntervalAfterReset() {
		final Duration chosenInterval = Duration.ofSeconds(7);
		final FailoverPolicy policy = FailoverPolicy.builder().withPrimaryRecheckInterval(chosenInterval)
				.withPrimaryRecheckBackoff(2, Duration.ofMinutes(1)).resetPrimaryRecheckBackoff().build();
		assertThat(policy.getPrimaryRecheckInterval()).isEqualTo(chosenInterval);
		assertThat(policy.getPrimaryMaximumRecheckInterval()).isEqualTo(chosenInterval);
		assertThat(policy.getPrimaryRecheckBackoffMultiplier()).isEqualTo(MailerGenericBuilder.DEFAULT_FAILOVER_PRIMARY_RECHECK_BACKOFF_MULTIPLIER);
	}

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
		final FailoverPolicy.Builder unfinished = FailoverPolicy.builder().withPrimaryRecheckBackoff(2, Duration.ZERO);
		assertThat(unfinished.getPrimaryMaximumRecheckInterval()).isEqualTo(Duration.ZERO);
		assertThatThrownBy(unfinished::build).isInstanceOf(IllegalArgumentException.class);
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
