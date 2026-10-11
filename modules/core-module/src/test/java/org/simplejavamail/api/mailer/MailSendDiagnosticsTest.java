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
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class MailSendDiagnosticsTest {
	@Test
	void connectionHistoryIsImmutableBoundedAndSerializationDoesNotEscapeTwice() throws Exception {
		final MailSendConnectionStep skipped = new MailSendConnectionStep("primary\nname", "internal\nrelay", 587,
				false, false, null, "Cached failure; recheck pending.\r\n" + "x".repeat(3000));
		final MailSendConnectionStep acquired = new MailSendConnectionStep("secondary", "private-relay", 2525,
				true, true, Duration.ZERO, null);
		final List<MailSendConnectionStep> steps = new ArrayList<>(List.of(skipped, acquired));
		final MailSendDiagnostics report = new MailSendDiagnostics(Duration.ofSeconds(1), measured(), measured(), measured(), measured(), measured(),
				measured(), null, null, measured(), steps, 4);
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
		assertThatThrownBy(() -> new MailSendConnectionStep(null, null, null, false, true, null, null))
				.isInstanceOf(IllegalArgumentException.class);
		assertThatThrownBy(() -> new MailSendConnectionStep(null, null, null, true, false, Duration.ZERO, null))
				.isInstanceOf(IllegalArgumentException.class);
		assertThatThrownBy(() -> new MailSendDiagnostics(Duration.ZERO, measured(), measured(), measured(), measured(), measured(), measured(),
				null, null, measured(), Collections.nCopies(65, acquired), 0)).isInstanceOf(IllegalArgumentException.class);
		assertThat(report(measured(), measured(), null, null).getConnectionHistory()).isEmpty();
	}

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
	void rendersTheExplicitMeasurementsInStableOrder() {
		assertThat(report(measured(), measured(), "private-relay.example", 587).toString()).isEqualTo(
				"Mail send timings:\n  Total: PT1S\n  Preparation: PT0S\n  Scheduling: PT0S"
						+ "\n  Sending-limit wait: not measured: Sending-limit waiting was not recorded in this snapshot.\n  Connection acquisition: PT0S"
						+ "\n  MIME preparation: PT0S\n  Submission: PT0S\n  Cleanup: PT0S");
	}

	@Test
	void rendersConnectionHistoryInOrderWithOmissionsButWithoutDestinationDetails() throws Exception {
		final List<MailSendConnectionStep> steps = List.of(
				new MailSendConnectionStep("primary-name", "primary-host", 587, true, false, Duration.ofMillis(24), "Connection refused."),
				new MailSendConnectionStep("other-name", "other-host", 2525, false, false, null, "Recheck pending."),
				new MailSendConnectionStep("failover-name", "failover-host", 465, true, true, Duration.ofMillis(8), null));
		final MailSendDiagnostics report = new MailSendDiagnostics(Duration.ofSeconds(1), measured(), measured(), measured(), measured(), measured(),
				measured(), "selected-host", 25, measured(), steps, 3);
		final String expectedHistory = "\n  Connection history:\n    - Connection failed in PT0.024S: Connection refused."
				+ "\n    - Destination skipped: Recheck pending.\n    - Connection acquired in PT0.008S\n    Intermediate steps omitted: 3";
		final MailSendDiagnostics withoutHistory = new MailSendDiagnostics(Duration.ofSeconds(1), measured(), measured(), measured(), measured(),
				measured(), measured(), "selected-host", 25, measured());
		assertThat(report.toString()).isEqualTo(withoutHistory + expectedHistory)
				.doesNotContain("primary-name", "primary-host", "other-name", "other-host", "failover-name", "failover-host", "selected-host", "587", "2525", "465");
		assertThat(roundTrip(report).toString()).isEqualTo(report.toString());
	}

	@Test
	void rendersHistoryWithoutAnOmissionMarkerWhenEveryStepWasRetained() {
		final MailSendConnectionStep acquired = new MailSendConnectionStep(null, null, null, true, true, Duration.ZERO, null);
		final MailSendDiagnostics report = new MailSendDiagnostics(Duration.ofSeconds(1), measured(), measured(), measured(), measured(), measured(),
				measured(), null, null, measured(), List.of(acquired), 0);
		assertThat(report.toString()).endsWith("\n  Connection history:\n    - Connection acquired in PT0S")
				.doesNotContain("Intermediate steps omitted:");
	}

	@Test
	void rendersAnOmittedCountEvenWhenNoConnectionStepsWereSupplied() {
		final MailSendDiagnostics report = new MailSendDiagnostics(Duration.ofSeconds(1), measured(), measured(), measured(), measured(), measured(),
				measured(), null, null, measured(), Collections.emptyList(), 2);
		assertThat(report.toString()).endsWith("\n  Connection history:\n    Intermediate steps omitted: 2");
	}

	@Test
	void rateWaitSurvivesSerializationAndParticipatesInTheSingleFailureInvariant() throws Exception {
		final MailSendMeasurement failedWait = new MailSendMeasurement(Duration.ofMillis(20), null, true);
		final MailSendDiagnostics report = new MailSendDiagnostics(Duration.ofSeconds(1), measured(), measured(), measured(), measured(), measured(),
				measured(), null, null, failedWait);
		assertThat(roundTrip(report).getRateLimitWait().getElapsed()).contains(Duration.ofMillis(20));
		assertThat(roundTrip(report).getRateLimitWait().isFailureObservedHere()).isTrue();
		assertThatThrownBy(() -> new MailSendDiagnostics(Duration.ofSeconds(1), failedWait, measured(), measured(), measured(), measured(),
				measured(), null, null, failedWait)).isInstanceOf(IllegalArgumentException.class);
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
