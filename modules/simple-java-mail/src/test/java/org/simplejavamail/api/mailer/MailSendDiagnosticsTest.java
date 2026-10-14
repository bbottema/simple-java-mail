package org.simplejavamail.api.mailer;

import org.simplejavamail.mailer.internal.MailSendConnectionStepImpl;

import org.simplejavamail.mailer.internal.MailSendOutcomeImpl;

import org.simplejavamail.mailer.internal.MailSendMeasurementImpl;

import org.junit.jupiter.api.Test;
import org.simplejavamail.mailer.internal.MailSendDiagnosticsImpl;
import org.simplejavamail.mailer.internal.MailSendDiagnosticsImpl.MailSendDiagnosticsImplBuilder;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.ObjectInputStream;
import java.io.ObjectOutputStream;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class MailSendDiagnosticsTest {
	@Test
	void connectionHistoryIsImmutableBoundedAndSerializationDoesNotEscapeTwice() throws Exception {
		final MailSendConnectionStep skipped = MailSendConnectionStepImpl.builder()
			.configuredName("primary\nname")
			.smtpHost("internal\nrelay")
			.smtpPort(587)
			.connectionAttempted(false)
			.connectionAcquired(false)
			.elapsed(null)
			.explanation("Cached failure; recheck pending.\r\n" + "x".repeat(3000))
			.build();
		final MailSendConnectionStep acquired = MailSendConnectionStepImpl.builder()
			.configuredName("secondary")
			.smtpHost("private-relay")
			.smtpPort(2525)
			.connectionAttempted(true)
			.connectionAcquired(true)
			.elapsed(Duration.ZERO)
			.explanation(null)
			.build();
		final List<MailSendConnectionStep> steps = new ArrayList<>(List.of(skipped, acquired));
		final MailSendDiagnostics report = reportBuilder().rateLimitWait(measured()).connectionHistory(steps).omittedConnectionSteps(4).build();
		steps.clear();
		assertThat(report.getConnectionHistory()).containsExactly(skipped, acquired);
		assertThatThrownBy(() -> report.getConnectionHistory().clear()).isInstanceOf(UnsupportedOperationException.class);
		final MailSendDiagnostics copy = roundTrip(report);
		assertThat(copy.getOmittedConnectionSteps()).isEqualTo(4);
		assertThat(copy.getConnectionHistory().get(0).getConfiguredName()).isEqualTo(skipped.getConfiguredName());
		assertThat(copy.getConnectionHistory().get(0).getExplanation()).isEqualTo(skipped.getExplanation());
		assertThat(copy.getConnectionHistory().get(1).getElapsed()).contains(Duration.ZERO);
		assertThat(copy.toString()).isEqualTo(report.toString());
		assertThat(copy.toString()).doesNotContain("private-relay", "primary", "internal", "secondary");
		assertThat(copy.toString()).contains("Cached failure; recheck pending.\\r\\n").doesNotContain("\r");
		assertThat(skipped.toString()).doesNotContain("internal", "primary", "\r", "\n");
		assertThatThrownBy(() -> MailSendConnectionStepImpl.builder()
			.configuredName(null)
			.smtpHost(null)
			.smtpPort(null)
			.connectionAttempted(false)
			.connectionAcquired(true)
			.elapsed(null)
			.explanation(null)
			.build())
				.isInstanceOf(IllegalArgumentException.class);
		assertThatThrownBy(() -> MailSendConnectionStepImpl.builder()
			.configuredName(null)
			.smtpHost(null)
			.smtpPort(null)
			.connectionAttempted(true)
			.connectionAcquired(false)
			.elapsed(Duration.ZERO)
			.explanation(null)
			.build())
				.isInstanceOf(IllegalArgumentException.class);
		assertThatThrownBy(() -> reportBuilder().connectionHistory(Collections.nCopies(65, acquired)).build()).isInstanceOf(IllegalArgumentException.class);
		assertThat(report(measured(), measured(), null, null).getConnectionHistory()).isEmpty();
	}

	@Test
	void rejectsTheFormerConcreteOutcomeSnapshot() throws Exception {
		// Serialized with the unmodified pre-#750 core JAR: UID 1 and no diagnostics field in its stream descriptor.
		try (InputStream fixture = getClass().getResourceAsStream("/mail-send-outcome-before-diagnostics.base64")) {
			assertThat(fixture).isNotNull();
			final byte[] bytes = Base64.getMimeDecoder().decode(fixture.readAllBytes());
			try (ObjectInputStream input = new ObjectInputStream(new ByteArrayInputStream(bytes))) {
				assertThatThrownBy(input::readObject).isInstanceOf(java.io.InvalidClassException.class);
			}
		}
	}

	@Test
	void zeroIsMeasuredAndAbsenceHasAnExplanation() {
		final MailSendMeasurement zero = MailSendMeasurementImpl.builder()
			.elapsed(Duration.ZERO)
			.unavailableReason(null)
			.failureObservedHere(false)
			.build();
		final MailSendMeasurement absent = MailSendMeasurementImpl.builder()
			.elapsed(null)
			.unavailableReason("Connection setup is outside this attempt.")
			.failureObservedHere(false)
			.build();
		assertThat(zero.getElapsed()).contains(Duration.ZERO);
		assertThat(zero.getUnavailableReason()).isEmpty();
		assertThat(absent.getElapsed()).isEmpty();
		assertThat(absent.getUnavailableReason()).contains("Connection setup is outside this attempt.");
	}

	@Test
	void validatesSuppliedDataInsteadOfInventingDefaults() {
		assertThatThrownBy(() -> MailSendMeasurementImpl.builder()
			.elapsed(null)
			.unavailableReason(null)
			.failureObservedHere(false)
			.build()).isInstanceOf(IllegalArgumentException.class);
		assertThatThrownBy(() -> MailSendMeasurementImpl.builder()
			.elapsed(Duration.ZERO)
			.unavailableReason("also absent")
			.failureObservedHere(false)
			.build()).isInstanceOf(IllegalArgumentException.class);
		assertThatThrownBy(() -> MailSendMeasurementImpl.builder()
			.elapsed(Duration.ofNanos(-1))
			.unavailableReason(null)
			.failureObservedHere(false)
			.build()).isInstanceOf(IllegalArgumentException.class);
		assertThatThrownBy(() -> MailSendMeasurementImpl.builder()
			.elapsed(null)
			.unavailableReason(" ")
			.failureObservedHere(false)
			.build()).isInstanceOf(IllegalArgumentException.class);
		assertThatThrownBy(() -> MailSendMeasurementImpl.builder()
			.elapsed(null)
			.unavailableReason("not reached")
			.failureObservedHere(true)
			.build()).isInstanceOf(IllegalArgumentException.class);
		final MailSendMeasurement failure = MailSendMeasurementImpl.builder()
			.elapsed(Duration.ZERO)
			.unavailableReason(null)
			.failureObservedHere(true)
			.build();
		assertThatThrownBy(() -> report(failure, failure, "host", 25)).isInstanceOf(IllegalArgumentException.class);
		assertThatThrownBy(() -> report(measured(), measured(), "host", -1)).isInstanceOf(IllegalArgumentException.class);
		assertThatThrownBy(() -> report(measured(), measured(), "host", 65536)).isInstanceOf(IllegalArgumentException.class);
		assertThatThrownBy(() -> reportBuilder().elapsed(Duration.ofSeconds(-1)).build())
				.isInstanceOf(IllegalArgumentException.class);
	}

	@Test
	void hasBoundedSafeTextAndKeepsEndpointOutOfAutomaticRendering() throws Exception {
		final MailSendMeasurement absent = MailSendMeasurementImpl.builder()
			.elapsed(null)
			.unavailableReason("outside\r\nforged\u202e" + "a".repeat(3000))
			.failureObservedHere(false)
			.build();
		final MailSendMeasurement failed = MailSendMeasurementImpl.builder()
			.elapsed(Duration.ofMillis(12))
			.unavailableReason(null)
			.failureObservedHere(true)
			.build();
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
	void rendersTheExplicitMeasurementsInStableOrder() {
		assertThat(report(measured(), measured(), "private-relay.example", 587).toString()).isEqualTo(
				"Mail send timings:\n  Total: PT1S\n  Preparation: PT0S\n  Scheduling: PT0S"
						+ "\n  Sending-limit wait: not measured: Sending-limit waiting was not recorded in this snapshot."
						+ "\n  Retry wait: not measured: Automatic retry waiting was not recorded for this send.\n  Connection acquisition: PT0S"
						+ "\n  MIME preparation: PT0S\n  Submission: PT0S\n  Cleanup: PT0S");
	}

	@Test
	void rendersConnectionHistoryInOrderWithOmissionsButWithoutDestinationDetails() throws Exception {
		final List<MailSendConnectionStep> steps = List.of(
				MailSendConnectionStepImpl.builder()
					.configuredName("primary-name")
					.smtpHost("primary-host")
					.smtpPort(587)
					.connectionAttempted(true)
					.connectionAcquired(false)
					.elapsed(Duration.ofMillis(24))
					.explanation("Connection refused.")
					.build(),
				MailSendConnectionStepImpl.builder()
					.configuredName("other-name")
					.smtpHost("other-host")
					.smtpPort(2525)
					.connectionAttempted(false)
					.connectionAcquired(false)
					.elapsed(null)
					.explanation("Recheck pending.")
					.build(),
				MailSendConnectionStepImpl.builder()
					.configuredName("failover-name")
					.smtpHost("failover-host")
					.smtpPort(465)
					.connectionAttempted(true)
					.connectionAcquired(true)
					.elapsed(Duration.ofMillis(8))
					.explanation(null)
					.build());
		final MailSendDiagnostics report = reportBuilder().smtpHost("selected-host").smtpPort(25).rateLimitWait(measured())
				.connectionHistory(steps).omittedConnectionSteps(3).build();
		final String expectedHistory = "\n  Connection history:\n    - Connection failed in PT0.024S: Connection refused."
				+ "\n    - Destination skipped: Recheck pending.\n    - Connection acquired in PT0.008S\n    Intermediate steps omitted: 3";
		final MailSendDiagnostics withoutHistory = reportBuilder().smtpHost("selected-host").smtpPort(25).rateLimitWait(measured()).build();
		assertThat(report.toString()).isEqualTo(withoutHistory + expectedHistory)
				.doesNotContain("primary-name", "primary-host", "other-name", "other-host", "failover-name", "failover-host", "selected-host", "587", "2525", "465");
		assertThat(roundTrip(report).toString()).isEqualTo(report.toString());
	}

	@Test
	void rendersHistoryWithoutAnOmissionMarkerWhenEveryStepWasRetained() {
		final MailSendConnectionStep acquired = MailSendConnectionStepImpl.builder()
			.configuredName(null)
			.smtpHost(null)
			.smtpPort(null)
			.connectionAttempted(true)
			.connectionAcquired(true)
			.elapsed(Duration.ZERO)
			.explanation(null)
			.build();
		final MailSendDiagnostics report = reportBuilder().rateLimitWait(measured()).connectionHistory(List.of(acquired)).build();
		assertThat(report.toString()).endsWith("\n  Connection history:\n    - Connection acquired in PT0S")
				.doesNotContain("Intermediate steps omitted:");
	}

	@Test
	void rendersAnOmittedCountEvenWhenNoConnectionStepsWereSupplied() {
		final MailSendDiagnostics report = reportBuilder().rateLimitWait(measured()).omittedConnectionSteps(2).build();
		assertThat(report.toString()).endsWith("\n  Connection history:\n    Intermediate steps omitted: 2");
	}

	@Test
	void rateWaitSurvivesSerializationAndParticipatesInTheSingleFailureInvariant() throws Exception {
		final MailSendMeasurement failedWait = MailSendMeasurementImpl.builder()
			.elapsed(Duration.ofMillis(20))
			.unavailableReason(null)
			.failureObservedHere(true)
			.build();
		final MailSendDiagnostics report = reportBuilder().rateLimitWait(failedWait).build();
		assertThat(roundTrip(report).getRateLimitWait().getElapsed()).contains(Duration.ofMillis(20));
		assertThat(roundTrip(report).getRateLimitWait().isFailureObservedHere()).isTrue();
		assertThatThrownBy(() -> reportBuilder().preparation(failedWait).rateLimitWait(failedWait).build()).isInstanceOf(IllegalArgumentException.class);
	}

	@Test
	void outcomeConstructorsAndSerializationRetainOptionalDiagnostics() throws Exception {
		final Throwable failure = new IllegalStateException("original failure");
		final MailSendOutcome legacyConstructor = MailSendOutcomeImpl.builder()
			.initialMessageId(null)
			.effectiveMessageId(null)
			.requestedAt(Instant.EPOCH)
			.readyAt(null)
			.startedAt(null)
			.completedAt(Instant.EPOCH)
			.successful(false)
			.loggingOnly(false)
			.submissionReceipt(null)
			.failure(failure)
			.build();
		assertThat(roundTrip(legacyConstructor).getDiagnostics()).isEmpty();
		final MailSendDiagnostics diagnostics = report(measured(), measured(), null, null);
		final MailSendOutcome outcome = MailSendOutcomeImpl.builder()
			.initialMessageId(null)
			.effectiveMessageId(null)
			.requestedAt(Instant.EPOCH)
			.readyAt(null)
			.startedAt(null)
			.completedAt(Instant.EPOCH)
			.successful(false)
			.loggingOnly(false)
			.submissionReceipt(null)
			.failure(failure)
			.diagnostics(diagnostics)
			.build();
		assertThat(outcome.getDiagnostics()).containsSame(diagnostics);
		assertThat(roundTrip(outcome).getDiagnostics().orElseThrow().toString()).isEqualTo(diagnostics.toString());
	}

	private static MailSendDiagnostics report(final MailSendMeasurement preparation, final MailSendMeasurement cleanup, final String host, final Integer port) {
		return reportBuilder().preparation(preparation).cleanup(cleanup).smtpHost(host).smtpPort(port).build();
	}

	private static MailSendDiagnosticsImplBuilder reportBuilder() {
		return MailSendDiagnosticsImpl.builder().elapsed(Duration.ofSeconds(1)).preparation(measured()).scheduling(measured())
				.connectionAcquisition(measured()).mimePreparation(measured()).submission(measured()).cleanup(measured());
	}

	private static MailSendMeasurement measured() {
		return MailSendMeasurementImpl.builder()
			.elapsed(Duration.ZERO)
			.unavailableReason(null)
			.failureObservedHere(false)
			.build();
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
