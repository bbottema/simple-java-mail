package org.simplejavamail.mailer.failover;

import jakarta.mail.MessagingException;
import jakarta.mail.Session;
import jakarta.mail.internet.MimeMessage;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.simplejavamail.MailException;
import org.simplejavamail.api.email.Email;
import org.simplejavamail.api.mailer.CustomMailer;
import org.simplejavamail.api.mailer.MailSendCancelledException;
import org.simplejavamail.api.mailer.MailSendOutcome;
import org.simplejavamail.api.mailer.MailSubmissionException;
import org.simplejavamail.api.mailer.Mailer;
import org.simplejavamail.api.mailer.config.FailoverPolicy;
import org.simplejavamail.api.mailer.config.OperationalConfig;
import testutil.smtp.RecordingSmtpServer;

import java.net.ConnectException;
import java.security.cert.CertificateException;
import java.util.Arrays;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import static java.util.concurrent.TimeUnit.SECONDS;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;
import static org.simplejavamail.mailer.failover.FailoverContractSupport.*;

/** Supplemental SPI fault tests: these do not claim to be real SMTP/TLS server tests. */
@Timeout(30)
class FailoverProviderContractTest {

    @ParameterizedTest
    @CsvSource({"false,false", "false,true", "true,false", "true,true"})
    void cancellationDuringFailedSetupCleanupRetainsItsEvidenceWithoutUsingFailover(final boolean observed, final boolean cleanupFails) throws Exception {
        final FailoverTestProvider.State state = new FailoverTestProvider.State();
        state.connectionFailure = new MessagingException("fixture connection refused", new ConnectException("fixture refusal"));
        state.cleanupFailure = cleanupFails ? new MessagingException("fixture disposal failed") : null;
        final CountDownLatch cleanupStarted = new CountDownLatch(1);
        final CountDownLatch finishCleanup = new CountDownLatch(1);
        state.beforeClose = () -> {
            cleanupStarted.countDown();
            try {
                await(finishCleanup);
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
                throw new AssertionError(interrupted);
            }
        };
        final List<MailSendOutcome> outcomes = new CopyOnWriteArrayList<>();
        try (RecordingSmtpServer secondary = new RecordingSmtpServer(); Mailer alternative = builder(secondary.port()).buildMailer()) {
            final var configured = FailoverTestProvider.builder(state).withFailoverMailer(alternative, policy());
            if (observed) {
                configured.withMailSendObserver(outcomes::add);
            }
            try (Mailer initiating = configured.buildMailer()) {
                try {
                    final var sending = initiating.async().sendMail(email());
                    await(cleanupStarted);
                    sending.requestCancellation();
                    finishCleanup.countDown();
                    final Throwable completionFailure = catchThrowable(() -> sending.getCompletion().get(10, SECONDS));
                    assertThat(completionFailure).isNotNull();
                    final Throwable failure = completionFailure.getCause();
                    assertThat(failure).isInstanceOf(MailSendCancelledException.class);
                    final List<Throwable> retainedCauses = Stream.iterate(failure, Objects::nonNull, Throwable::getCause).collect(Collectors.toList());
                    assertThat(retainedCauses).contains(state.connectionFailure);
                    final List<Throwable> suppressed = retainedCauses.stream().flatMap(cause -> Arrays.stream(cause.getSuppressed()))
                            .collect(Collectors.toList());
                    if (cleanupFails) {
                        assertThat(suppressed).contains(state.cleanupFailure);
                    } else {
                        assertThat(suppressed).isEmpty();
                    }
                    assertThat(state.connections).hasValue(1);
                    assertThat(state.closes).hasValue(1);
                    assertThat(state.invocations).hasValue(0);
                    assertThat(secondary.connectionCount()).isZero();
                    if (observed) {
                        assertThat(outcomes).hasSize(1);
                        assertThat(outcomes.get(0).getFailure()).containsSame(failure);
                        assertThat(outcomes.get(0).getSubmissionReceipt()).isEmpty();
                    }
                } finally {
                    finishCleanup.countDown();
                    state.beforeClose = () -> { };
                    state.cleanupFailure = null;
                }
            }
        }
    }

    @Test
    void providerPreflightFailureCannotBeReinterpretedAsAConnectionOutage() throws Exception {
        final FailoverTestProvider.State state = new FailoverTestProvider.State();
        state.submissionFailure = new MessagingException("provider preflight failed", new ConnectException("nested connection-looking cause"));
        final List<MailSendOutcome> outcomes = new CopyOnWriteArrayList<>();
        try (RecordingSmtpServer secondary = new RecordingSmtpServer(); Mailer selected = builder(secondary.port()).buildMailer();
             Mailer initiating = FailoverTestProvider.builder(state).withFailoverMailer(selected, policy())
                     .withMailSendObserver(outcomes::add).buildMailer()) {
            final Throwable failure = catchThrowable(() -> initiating.sync().sendMail(email()));
            assertThat(failure).isInstanceOf(MailSubmissionException.class).hasCause(state.submissionFailure);
            assertThat(outcomes).hasSize(1);
            assertThat(outcomes.get(0).getFailure()).containsSame(failure);
            assertThat(outcomes.get(0).getSubmissionReceipt()).containsSame(((MailSubmissionException) failure).getSubmissionReceipt());
            assertThat(state.invocations).hasValue(1);
            assertThat(secondary.connectionCount()).isZero();
        }
    }

    @Test
    void unknownSetupEvidenceStopsEvenWithSecurityOptIns() throws Exception {
        final FailoverTestProvider.State state = new FailoverTestProvider.State();
        state.connectionFailure = new MessagingException("opaque setup failure");
        try (RecordingSmtpServer secondary = new RecordingSmtpServer(); Mailer selected = builder(secondary.port()).buildMailer();
             Mailer initiating = FailoverTestProvider.builder(state).withFailoverMailer(selected,
                     FailoverPolicy.builder().withFailoverOnAuthenticationFailure(true).withFailoverOnCertificateVerificationFailure(true).build())
                     .buildMailer()) {
            assertThat(catchThrowable(() -> initiating.sync().sendMail(email()))).isInstanceOf(MailException.class);
            assertThat(state.invocations).hasValue(0);
            assertThat(secondary.connectionCount()).isZero();
        }
    }

    @ParameterizedTest
    @CsvSource({"false,false", "false,true", "true,false", "true,true"})
    void certificateEvidenceUsesOnlyItsOwnPermission(final boolean allowCertificate, final boolean allowAuthentication) throws Exception {
        final FailoverTestProvider.State state = new FailoverTestProvider.State();
        state.connectionFailure = new MessagingException("certificate verification failed", new CertificateException("expired"));
        try (RecordingSmtpServer secondary = new RecordingSmtpServer(); Mailer selected = builder(secondary.port()).buildMailer();
             Mailer initiating = FailoverTestProvider.builder(state).withFailoverMailer(selected, FailoverPolicy.builder()
                     .withFailoverOnAuthenticationFailure(allowAuthentication).withFailoverOnCertificateVerificationFailure(allowCertificate).build())
                     .buildMailer()) {
            assertThat(catchThrowable(() -> initiating.sync().sendMail(email())) == null).isEqualTo(allowCertificate);
            assertThat(state.invocations).hasValue(0);
            assertThat(secondary.messages()).hasSize(allowCertificate ? 1 : 0);
        }
    }

    @Test
    void caughtCallbackSubmissionFailureDoesNotHideScopeCleanupFailure() throws Exception {
        final FailoverTestProvider.State state = new FailoverTestProvider.State();
        state.submissionFailure = new MessagingException("submission failed");
        state.cleanupFailure = new MessagingException("scope cleanup failed");
        final AtomicReference<Throwable> caughtSubmission = new AtomicReference<>();
        final List<MailSendOutcome> outcomes = new CopyOnWriteArrayList<>();
        try (Mailer mailer = FailoverTestProvider.builder(state).withMailSendObserver(outcomes::add).buildMailer()) {
            try {
                final Throwable scopeFailure = catchThrowable(() -> mailer.withOpenConnection(sender ->
                        caughtSubmission.set(catchThrowable(() -> sender.sendMail(email())))));
                assertThat(scopeFailure).hasRootCause(state.cleanupFailure);
                assertThat(caughtSubmission.get()).isInstanceOf(MailSubmissionException.class);
                assertThat(outcomes).hasSize(1);
                assertThat(outcomes.get(0).getFailure()).containsSame(caughtSubmission.get());
            } finally {
                state.cleanupFailure = null;
            }
        }
    }

    @Test
    void customMailerIsInvokedOnceAndLoggingOnlyDoesNotConnect() throws Exception {
        final AtomicInteger calls = new AtomicInteger();
        final List<MailSendOutcome> outcomes = new CopyOnWriteArrayList<>();
        final IllegalStateException callbackFailure = new IllegalStateException("external service failed");
        final CustomMailer custom = new CustomMailer() {
            @Override
            public void testConnection(final OperationalConfig configuration, final Session session) {
                throw new AssertionError("not a connection probe");
            }

            @Override
            public void sendMessage(final OperationalConfig configuration, final Session session, final Email email, final MimeMessage message) {
                calls.incrementAndGet();
                throw callbackFailure;
            }
        };
        try (RecordingSmtpServer secondary = new RecordingSmtpServer(); Mailer selected = builder(secondary.port()).buildMailer();
             Mailer invoking = builder(secondary.port()).withCustomMailer(custom).withFailoverMailer(selected, policy())
                     .withMailSendObserver(outcomes::add).buildMailer();
             Mailer loggingOnly = builder(secondary.port()).withTransportModeLoggingOnly(true).withFailoverMailer(selected, policy()).buildMailer()) {
            final Throwable failure = catchThrowable(() -> invoking.sync().sendMail(email()));
            assertThat(failure).isInstanceOf(MailException.class).hasCause(callbackFailure);
            assertThat(outcomes).hasSize(1);
            assertThat(outcomes.get(0).getFailure()).containsSame(failure);
            assertThat(calls).hasValue(1);
            loggingOnly.sync().sendMail(email());
            assertThat(secondary.connectionCount()).isZero();
        }
    }
}
