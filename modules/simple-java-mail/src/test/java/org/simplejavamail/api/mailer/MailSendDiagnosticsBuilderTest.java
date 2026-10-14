package org.simplejavamail.api.mailer;

import org.simplejavamail.mailer.internal.MailSendSubmissionStepImpl;

import org.simplejavamail.mailer.internal.MailSendConnectionStepImpl;

import org.simplejavamail.mailer.internal.MailSendMeasurementImpl;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.simplejavamail.mailer.internal.MailSendDiagnosticsImpl;
import org.simplejavamail.mailer.internal.MailSendDiagnosticsImpl.MailSendDiagnosticsImplBuilder;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.ObjectInputStream;
import java.io.ObjectOutputStream;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class MailSendDiagnosticsBuilderTest {

	@Test
	void namedAssignmentsRetainDistinctMeasurementsEndpointsAndHistories() throws Exception {
		final MailSendMeasurement preparation = measured(1);
		final MailSendMeasurement scheduling = measured(2);
		final MailSendMeasurement acquisition = measured(3);
		final MailSendMeasurement mime = measured(4);
		final MailSendMeasurement submission = measured(5);
		final MailSendMeasurement cleanup = measured(6);
		final MailSendMeasurement rateWait = measured(7);
		final MailSendMeasurement retryWait = measured(8);
		final MailSendConnectionStep connection = connectionStep();
		final MailSendSubmissionStep invocation = submissionStep();
		final MailSendDiagnostics report = MailSendDiagnosticsImpl.builder()
			.elapsed(Duration.ofSeconds(1))
			.preparation(preparation)
			.scheduling(scheduling)
			.connectionAcquisition(acquisition)
			.mimePreparation(mime)
			.submission(submission)
			.cleanup(cleanup)
			.rateLimitWait(rateWait)
			.retryWait(retryWait)
			.smtpHost("private\nrelay")
			.smtpPort(2525)
			.connectionHistory(List.of(connection))
			.omittedConnectionSteps(2)
			.submissionHistory(List.of(invocation))
			.omittedSubmissionSteps(3)
			.build();

		assertThat(report.getElapsed()).isEqualTo(Duration.ofSeconds(1));
		assertThat(report.getPreparation()).isSameAs(preparation);
		assertThat(report.getScheduling()).isSameAs(scheduling);
		assertThat(report.getConnectionAcquisition()).isSameAs(acquisition);
		assertThat(report.getMimePreparation()).isSameAs(mime);
		assertThat(report.getSubmission()).isSameAs(submission);
		assertThat(report.getCleanup()).isSameAs(cleanup);
		assertThat(report.getRateLimitWait()).isSameAs(rateWait);
		assertThat(report.getRetryWait()).isSameAs(retryWait);
		assertThat(report.getSmtpHost()).contains("private\\nrelay");
		assertThat(report.getSmtpPort()).contains(2525);
		assertThat(report.getConnectionHistory()).containsExactly(connection);
		assertThat(report.getSubmissionHistory()).containsExactly(invocation);
		assertThat(report.getOmittedConnectionSteps()).isEqualTo(2);
		assertThat(report.getOmittedSubmissionSteps()).isEqualTo(3);

		final MailSendDiagnostics copy = roundTrip(report);
		assertThat(copy.toString()).isEqualTo(report.toString());
		assertThat(copy.getSmtpHost()).isEqualTo(report.getSmtpHost());
		assertThat(copy.getSmtpPort()).isEqualTo(report.getSmtpPort());
		assertThat(copy.getRateLimitWait().getElapsed()).contains(Duration.ofMillis(7));
		assertThat(copy.getRetryWait().getElapsed()).contains(Duration.ofMillis(8));
		assertThat(copy.getOmittedConnectionSteps()).isEqualTo(2);
		assertThat(copy.getOmittedSubmissionSteps()).isEqualTo(3);
	}

	@Test
	void optionalFactsStartAbsentWithoutInventingMeasurements() {
		final MailSendDiagnostics built = requiredBuilder().build();
		assertThat(built.getRateLimitWait().getElapsed()).isEmpty();
		assertThat(built.getRateLimitWait().getUnavailableReason()).isPresent();
		assertThat(built.getRetryWait().getElapsed()).isEmpty();
		assertThat(built.getRetryWait().getUnavailableReason()).isPresent();
		assertThat(built.getSmtpHost()).isEmpty();
		assertThat(built.getSmtpPort()).isEmpty();
		assertThat(built.getConnectionHistory()).isEmpty();
		assertThat(built.getSubmissionHistory()).isEmpty();
		assertThat(built.getOmittedConnectionSteps()).isZero();
		assertThat(built.getOmittedSubmissionSteps()).isZero();
	}

	@ParameterizedTest
	@ValueSource(strings = { "elapsed", "preparation", "scheduling", "connectionAcquisition", "mimePreparation", "submission", "cleanup" })
	void eachOriginalObservationMustBeSupplied(final String omitted) {
		final MailSendDiagnosticsImplBuilder builder = MailSendDiagnosticsImpl.builder();
		requiredAssignments().forEach((name, assign) -> {
			if (!name.equals(omitted)) { assign.accept(builder); }
		});
		assertThatThrownBy(builder::build).isInstanceOfAny(NullPointerException.class, IllegalArgumentException.class);
	}

	@Test
	void copiesCollectionsAtEachBuildAndDoesNotShareSnapshotMutation() throws Exception {
		final List<MailSendConnectionStep> connections = new ArrayList<>(List.of(connectionStep()));
		final List<MailSendSubmissionStep> submissions = new ArrayList<>(List.of(submissionStep()));
		final MailSendDiagnosticsImplBuilder builder = requiredBuilder().connectionHistory(connections).submissionHistory(submissions);
		final MailSendDiagnostics first = builder.build();
		connections.clear();
		submissions.clear();
		final MailSendDiagnostics second = builder.elapsed(Duration.ofSeconds(2)).build();
		assertThat(first.getConnectionHistory()).hasSize(1);
		assertThat(first.getSubmissionHistory()).hasSize(1);
		assertThat(first.getElapsed()).isEqualTo(Duration.ZERO);
		assertThat(second.getConnectionHistory()).isEmpty();
		assertThat(second.getSubmissionHistory()).isEmpty();
		assertThat(second.getElapsed()).isEqualTo(Duration.ofSeconds(2));
		assertThatThrownBy(() -> first.getConnectionHistory().clear()).isInstanceOf(UnsupportedOperationException.class);
		assertThatThrownBy(() -> first.getSubmissionHistory().clear()).isInstanceOf(UnsupportedOperationException.class);
		final MailSendDiagnostics copy = roundTrip(first);
		assertThatThrownBy(() -> copy.getConnectionHistory().clear()).isInstanceOf(UnsupportedOperationException.class);
		assertThatThrownBy(() -> copy.getSubmissionHistory().clear()).isInstanceOf(UnsupportedOperationException.class);
	}

	@Test
	void reusesConstructorValidationForDurationsPortsFailureMarkersAndHistoryBounds() {
		assertThatThrownBy(() -> requiredBuilder().elapsed(Duration.ofNanos(-1)).build()).isInstanceOf(IllegalArgumentException.class);
		assertThatThrownBy(() -> requiredBuilder().smtpPort(0).build()).isInstanceOf(IllegalArgumentException.class);
		assertThatThrownBy(() -> requiredBuilder().smtpPort(65536).build()).isInstanceOf(IllegalArgumentException.class);
		final MailSendMeasurement failed = MailSendMeasurementImpl.builder()
			.elapsed(Duration.ZERO)
			.unavailableReason(null)
			.failureObservedHere(true)
			.build();
		assertThatThrownBy(() -> requiredBuilder().preparation(failed).retryWait(failed).build()).isInstanceOf(IllegalArgumentException.class);
		assertThatThrownBy(() -> requiredBuilder().connectionHistory(Collections.nCopies(65, connectionStep())).build())
				.isInstanceOf(IllegalArgumentException.class);
		assertThatThrownBy(() -> requiredBuilder().submissionHistory(Collections.nCopies(65, submissionStep())).build())
				.isInstanceOf(IllegalArgumentException.class);
		assertThatThrownBy(() -> requiredBuilder().omittedConnectionSteps(-1).build()).isInstanceOf(IllegalArgumentException.class);
		assertThatThrownBy(() -> requiredBuilder().omittedSubmissionSteps(-1).build()).isInstanceOf(IllegalArgumentException.class);
	}

	@Test
	void aFreshBuilderCannotInheritAnotherBuildersOptionalFacts() {
		requiredBuilder().smtpHost("private-relay").smtpPort(587).submissionHistory(List.of(submissionStep())).build();
		final MailSendDiagnostics separate = requiredBuilder().build();
		assertThat(separate.getSmtpHost()).isEmpty();
		assertThat(separate.getSmtpPort()).isEmpty();
		assertThat(separate.getSubmissionHistory()).isEmpty();
	}

	@Test
	void constructionIsAbsentFromTheReadContract() {
		assertThat(MailSendDiagnostics.class.getDeclaredMethods()).noneMatch(method -> method.getName().equals("builder"));
		assertThat(MailSendDiagnostics.class.getDeclaredClasses()).isEmpty();
		assertThat(MailSendDiagnostics.class.isInterface()).isTrue();
		assertThat(MailSendDiagnostics.class.getConstructors()).isEmpty();
		assertThat(MailSendDiagnosticsImplBuilder.class.getPackageName()).endsWith(".internal");
	}

	private static MailSendDiagnosticsImplBuilder requiredBuilder() {
		final MailSendDiagnosticsImplBuilder builder = MailSendDiagnosticsImpl.builder();
		requiredAssignments().values().forEach(assign -> assign.accept(builder));
		return builder;
	}

	private static Map<String, Consumer<MailSendDiagnosticsImplBuilder>> requiredAssignments() {
		return Map.of(
				"elapsed", builder -> builder.elapsed(Duration.ZERO),
				"preparation", builder -> builder.preparation(measured(0)),
				"scheduling", builder -> builder.scheduling(measured(0)),
				"connectionAcquisition", builder -> builder.connectionAcquisition(measured(0)),
				"mimePreparation", builder -> builder.mimePreparation(measured(0)),
				"submission", builder -> builder.submission(measured(0)),
				"cleanup", builder -> builder.cleanup(measured(0)));
	}

	private static MailSendMeasurement measured(final long millis) {
		return MailSendMeasurementImpl.builder()
			.elapsed(Duration.ofMillis(millis))
			.unavailableReason(null)
			.failureObservedHere(false)
			.build();
	}

	private static MailSendConnectionStep connectionStep() {
		return MailSendConnectionStepImpl.builder()
			.configuredName(null)
			.smtpHost(null)
			.smtpPort(null)
			.connectionAttempted(true)
			.connectionAcquired(true)
			.elapsed(Duration.ZERO)
			.explanation(null)
			.build();
	}

	private static MailSendSubmissionStep submissionStep() {
		return MailSendSubmissionStepImpl.builder()
			.attemptNumber(1)
			.submissionStatus(MailSubmissionStatus.REJECTED)
			.smtpReplyCode(451)
			.elapsed(Duration.ZERO)
			.retryDelay(Duration.ofSeconds(30))
			.explanation(null)
			.build();
	}

	private static MailSendDiagnostics roundTrip(final MailSendDiagnostics report) throws Exception {
		final ByteArrayOutputStream bytes = new ByteArrayOutputStream();
		try (ObjectOutputStream output = new ObjectOutputStream(bytes)) { output.writeObject(report); }
		try (ObjectInputStream input = new ObjectInputStream(new ByteArrayInputStream(bytes.toByteArray()))) {
			return (MailSendDiagnostics) input.readObject();
		}
	}
}
