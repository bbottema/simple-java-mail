package org.simplejavamail.api.mailer;

import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.ObjectInputStream;
import java.io.ObjectOutputStream;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class MailSendDiagnosticsTest {
	@Test
	void readsAnActualPreDiagnosticsSerializedOutcome() throws Exception {
		// Serialized with the unmodified pre-#750 core JAR: UID 1 and no diagnostics field in its stream descriptor.
		try (InputStream fixture = getClass().getResourceAsStream("/mail-send-outcome-before-diagnostics.base64")) {
			assertThat(fixture).isNotNull();
			final byte[] bytes = Base64.getMimeDecoder().decode(fixture.readAllBytes());
			try (ObjectInputStream input = new ObjectInputStream(new ByteArrayInputStream(bytes))) {
				final MailSendOutcome outcome = (MailSendOutcome) input.readObject();
				assertThat(outcome.getDiagnostics()).isEmpty();
				assertThat(outcome.getFailure().orElseThrow()).hasMessage("old outcome fixture");
				assertThat(outcome.getRequestedAt()).isEqualTo(Instant.EPOCH);
			}
		}
	}

	@Test
	void zeroIsMeasuredAndAbsenceHasAnExplanation() {
		final MailSendMeasurement zero = new MailSendMeasurement(Duration.ZERO, null, false);
		final MailSendMeasurement absent = new MailSendMeasurement(null, "Connection setup is outside this attempt.", false);
		assertThat(zero.getElapsed()).contains(Duration.ZERO);
		assertThat(zero.getUnavailableReason()).isEmpty();
		assertThat(absent.getElapsed()).isEmpty();
		assertThat(absent.getUnavailableReason()).contains("Connection setup is outside this attempt.");
	}

	@Test
	void validatesSuppliedDataInsteadOfInventingDefaults() {
		assertThatThrownBy(() -> new MailSendMeasurement(null, null, false)).isInstanceOf(IllegalArgumentException.class);
		assertThatThrownBy(() -> new MailSendMeasurement(Duration.ZERO, "also absent", false)).isInstanceOf(IllegalArgumentException.class);
		assertThatThrownBy(() -> new MailSendMeasurement(Duration.ofNanos(-1), null, false)).isInstanceOf(IllegalArgumentException.class);
		assertThatThrownBy(() -> new MailSendMeasurement(null, " ", false)).isInstanceOf(IllegalArgumentException.class);
		assertThatThrownBy(() -> new MailSendMeasurement(null, "not reached", true)).isInstanceOf(IllegalArgumentException.class);
		final MailSendMeasurement failure = new MailSendMeasurement(Duration.ZERO, null, true);
		assertThatThrownBy(() -> report(failure, failure, "host", 25)).isInstanceOf(IllegalArgumentException.class);
		assertThatThrownBy(() -> report(measured(), measured(), "host", -1)).isInstanceOf(IllegalArgumentException.class);
		assertThatThrownBy(() -> report(measured(), measured(), "host", 65536)).isInstanceOf(IllegalArgumentException.class);
		assertThatThrownBy(() -> new MailSendDiagnostics(Duration.ofSeconds(-1), measured(), measured(), measured(), measured(), measured(), measured(), null, null))
				.isInstanceOf(IllegalArgumentException.class);
	}

	@Test
	void hasBoundedSafeTextAndKeepsEndpointOutOfAutomaticRendering() throws Exception {
		final MailSendMeasurement absent = new MailSendMeasurement(null, "outside\r\nforged\u202e" + "a".repeat(3000), false);
		final MailSendMeasurement failed = new MailSendMeasurement(Duration.ofMillis(12), null, true);
		final MailSendDiagnostics report = report(absent, failed, "internal\nrelay.example", 587);
		assertThat(report.getSmtpHost()).contains("internal\\nrelay.example");
		assertThat(absent.getUnavailableReason().orElseThrow()).hasSizeLessThan(2060).doesNotContain("\n", "\r", "\u202e");
		assertThat(report.toString()).contains("Total:", "Preparation:", "Submission:", "Cleanup:", "failure observed here")
				.doesNotContain("internal", "587", "\r", "\u202e");
		final MailSendDiagnostics copy = roundTrip(report);
		assertThat(copy.toString()).isEqualTo(report.toString());
		assertThat(copy.getSmtpHost()).isEqualTo(report.getSmtpHost());
		assertThat(copy.getCleanup().isFailureObservedHere()).isTrue();
	}

	@Test
	void rendersTheSixExplicitMeasurementsInStableOrder() {
		assertThat(report(measured(), measured(), "private-relay.example", 587).toString()).isEqualTo(
				"Mail send timings:\n  Total: PT1S\n  Preparation: PT0S\n  Scheduling: PT0S\n  Connection acquisition: PT0S"
						+ "\n  MIME preparation: PT0S\n  Submission: PT0S\n  Cleanup: PT0S");
	}

	@Test
	void outcomeConstructorsAndSerializationRetainOptionalDiagnostics() throws Exception {
		final Throwable failure = new IllegalStateException("original failure");
		final MailSendOutcome legacyConstructor = new MailSendOutcome(null, null, Instant.EPOCH, null, null, Instant.EPOCH, false, false, null, failure);
		assertThat(roundTrip(legacyConstructor).getDiagnostics()).isEmpty();
		final MailSendDiagnostics diagnostics = report(measured(), measured(), null, null);
		final MailSendOutcome outcome = new MailSendOutcome(null, null, Instant.EPOCH, null, null, Instant.EPOCH, false, false, null, failure, diagnostics);
		assertThat(outcome.getDiagnostics()).containsSame(diagnostics);
		assertThat(roundTrip(outcome).getDiagnostics().orElseThrow().toString()).isEqualTo(diagnostics.toString());
	}

	private static MailSendDiagnostics report(final MailSendMeasurement preparation, final MailSendMeasurement cleanup, final String host, final Integer port) {
		return new MailSendDiagnostics(Duration.ofSeconds(1), preparation, measured(), measured(), measured(), measured(), cleanup, host, port);
	}

	private static MailSendMeasurement measured() {
		return new MailSendMeasurement(Duration.ZERO, null, false);
	}

	@SuppressWarnings("unchecked")
	private static <T> T roundTrip(final T value) throws Exception {
		final ByteArrayOutputStream bytes = new ByteArrayOutputStream();
		try (ObjectOutputStream output = new ObjectOutputStream(bytes)) {
			output.writeObject(value);
		}
		try (ObjectInputStream input = new ObjectInputStream(new ByteArrayInputStream(bytes.toByteArray()))) {
			return (T) input.readObject();
		}
	}
}
