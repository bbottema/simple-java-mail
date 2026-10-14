package org.simplejavamail.mailer.retry;

import jakarta.mail.Message.RecipientType;

import jakarta.mail.Session;
import org.simplejavamail.MailException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.simplejavamail.api.SimpleJavaMail;
import org.simplejavamail.api.email.Email;
import org.simplejavamail.api.mailer.Mailer;
import org.simplejavamail.api.mailer.MailSendOutcome;
import org.simplejavamail.api.mailer.MailSubmissionException;
import org.simplejavamail.api.mailer.MailSubmissionStatus;
import org.simplejavamail.api.mailer.config.MailSendRetryPolicy;
import org.simplejavamail.config.ConfigLoader;
import org.simplejavamail.internal.moduleloader.ModuleLoader;
import org.simplejavamail.recipient.internal.RecipientBuilderImpl;
import testutil.retry.RetryProviderTransport;

import java.time.Duration;
import java.util.List;
import java.util.Arrays;
import java.io.File;
import java.nio.file.Path;
import java.nio.file.Files;
import java.util.regex.Pattern;
import java.util.concurrent.CopyOnWriteArrayList;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.catchThrowable;
import static java.util.concurrent.TimeUnit.SECONDS;
import static java.util.stream.Collectors.joining;

@Timeout(30)
class RetryProviderBoundaryContractTest {
    private final SimpleJavaMail mail = SimpleJavaMail.withConfig(ConfigLoader.builder().load());

    private Mailer mailer(final RetryProviderTransport.State state, final List<MailSendOutcome> outcomes) throws Exception {
        final Session session = RetryProviderTransport.session(state);
        return mail.mailerBuilder(session).withConnectionPoolCoreSize(0).withConnectionPoolMaxSize(1).withConnectionPoolExpireAfterMillis(60000)
                .withMailSendRetryPolicy(MailSendRetryPolicy.builder().withMaximumAttempts(3).withRetryDelay(Duration.ofMillis(1)).build())
                .withMailSendObserver(outcomes::add).buildMailer();
    }

    private Email email() {
        return mail.emailBuilder().startingBlank().from("sender@example.test")
                .withRecipients(SimpleJavaMail.fromDefaults().recipientBuilder().withName(null).withAddress("first@example.test").withType(RecipientType.TO).build(), SimpleJavaMail.fromDefaults().recipientBuilder().withName(null).withAddress("second@example.test").withType(RecipientType.TO).build())
                .withPlainText("provider-neutral body").buildEmail();
    }
    @Test
    void independentProviderFreezesOnceAndRetriesUsingStructuredEvidence() throws Exception {
        final RetryProviderTransport.State state = new RetryProviderTransport.State();
        final List<MailSendOutcome> outcomes = new CopyOnWriteArrayList<>();
        try (Mailer mailer = mailer(state, outcomes)) {
            final var receipt = mailer.async().sendMail(email()).getCompletion().get(10, SECONDS);
            assertThat(state.invocations).hasValue(2);
            assertThat(state.captures).hasValue(1);
            assertThat(state.content.get(1)).isEqualTo(state.content.get(0));
            assertThat(outcomes).hasSize(1);
            assertThat(outcomes.get(0).getSubmissionReceipt()).containsSame(receipt);
        }
    }
    @Test
    void unsupportedReplayPartialAndUncertainResultsNeverInvokeAgain() throws Exception {
        for (String scenario : new String[] { "unsupported replay", "partial acceptance", "uncertain acceptance", "permanent refusal" }) {
            final RetryProviderTransport.State state = new RetryProviderTransport.State();
            state.repeatable = !scenario.equals("unsupported replay");
            state.partial = scenario.equals("partial acceptance");
            state.uncertain = scenario.equals("uncertain acceptance");
            state.permanent = scenario.equals("permanent refusal");
            try (Mailer mailer = mailer(state, new CopyOnWriteArrayList<>())) {
                assertThatThrownBy(() -> mailer.sync().sendMail(email())).as(scenario).isInstanceOf(MailException.class);
                assertThat(state.invocations).as(scenario).hasValue(state.repeatable ? 1 : 0);
            }
        }
    }

    @Test
    void waitingCapacityFailuresPreserveTruthAndReleaseTheirPermits() throws Exception {
        final RetryProviderTransport.State state = new RetryProviderTransport.State();
        state.temporaryRefusals = Integer.MAX_VALUE;
        final Session session = RetryProviderTransport.session(state);
        final List<MailSendOutcome> outcomes = new CopyOnWriteArrayList<>();
        try (Mailer mailer = mail.mailerBuilder(session).withConnectionPoolCoreSize(0).withConnectionPoolMaxSize(1)
                .withConnectionPoolExpireAfterMillis(60000).withThreadPoolSize(1)
                .withMailSendRetryPolicy(MailSendRetryPolicy.builder().withMaximumAttempts(3).withRetryDelay(Duration.ofHours(1))
                        .withWaitingCapacity(1).build())
                .withMailSendObserver(outcomes::add).buildMailer()) {
            final var first = mailer.async().sendMail(email());
            // Wait for the first execution to return its worker, not merely for the provider to record a refusal.
            mailer.getOperationalConfig().getExecutorService().submit(() -> { }).get(10, SECONDS);
            final var second = mailer.async().sendMail(email());
            final Throwable failure = second.getCompletion().handle((value, cause) -> cause).get(10, SECONDS);
            assertThat(failure).isInstanceOf(MailSubmissionException.class);
            assertThat(failure.getSuppressed()).anySatisfy(reason -> assertThat(reason).hasMessageContaining("waiting capacity"));
            assertThat(state.invocations).hasValue(2);
            assertThat(outcomes).hasSize(1);
            assertThat(outcomes.get(0).getFailure()).containsSame(failure);
            assertThat(outcomes.get(0).getDiagnostics().orElseThrow().getSubmissionHistory().get(0).getRetryDelay()).isEmpty();
            first.requestCancellation();
            first.getCompletion().handle((value, cause) -> cause).get(5, SECONDS);
            final var third = mailer.async().sendMail(email());
            mailer.getOperationalConfig().getExecutorService().submit(() -> { }).get(10, SECONDS);
            assertThat(third.getCompletion()).isNotDone();
            third.requestCancellation();
            third.getCompletion().handle((value, cause) -> cause).get(5, SECONDS);
            assertThat(state.invocations).hasValue(3);
        }
    }
    @Test
    void acceptedOrRefusedSubmissionFollowedByCleanupFailureNeverReplays() throws Exception {
        if (ModuleLoader.batchModuleAvailable()) {
            // Healthy pool return is not socket closure. Exercise real close failures in a fresh JVM without the optional batch module.
            verifyDirectCleanupInFreshJvm();
            return;
        }
        for (boolean accepted : new boolean[] { true, false }) {
            final RetryProviderTransport.State state = new RetryProviderTransport.State();
            state.temporaryRefusals = accepted ? 0 : 10;
            state.failCleanup = true;
            final List<MailSendOutcome> outcomes = new CopyOnWriteArrayList<>();
            try (Mailer mailer = mailer(state, outcomes)) {
                final Throwable failure = catchThrowable(() -> mailer.sync().sendMail(email()));
                assertThat(failure).isNotNull();
                assertThat(state.invocations).hasValue(1);
                assertThat(outcomes).hasSize(1);
                assertThat(outcomes.get(0).getFailure()).containsSame(failure);
                assertThat(outcomes.get(0).getSubmissionReceipt().orElseThrow().getStatus())
                        .isEqualTo(accepted ? MailSubmissionStatus.ACCEPTED : MailSubmissionStatus.REJECTED);
                assertThat(outcomes.get(0).getDiagnostics().orElseThrow().getSubmissionHistory().get(0).getRetryDelay()).isEmpty();
                if (!accepted) { assertThat(failure).isInstanceOf(MailSubmissionException.class); }
            }
        }
    }

    private void verifyDirectCleanupInFreshJvm() throws Exception {
        final String classpath = Arrays.stream(System.getProperty("surefire.test.class.path", System.getProperty("java.class.path"))
                .split(Pattern.quote(File.pathSeparator)))
                .filter(path -> !path.replace('\\', '/').contains("/batch-module/") && !path.contains("batch-module-"))
                .collect(joining(File.pathSeparator));
        final Path output = Path.of("target", "756-direct-retry-provider.log");
        final Process child = new ProcessBuilder(Path.of(System.getProperty("java.home"), "bin", "java").toString(),
                "-cp", classpath, getClass().getName()).redirectErrorStream(true).redirectOutput(output.toFile()).start();
        assertThat(child.waitFor(25, SECONDS)).isTrue();
        assertThat(child.exitValue()).as(Files.readString(output)).isZero();
    }

    public static void main(final String[] args) throws Exception {
        final RetryProviderBoundaryContractTest contracts = new RetryProviderBoundaryContractTest();
        contracts.independentProviderFreezesOnceAndRetriesUsingStructuredEvidence();
        contracts.acceptedOrRefusedSubmissionFollowedByCleanupFailureNeverReplays();
    }
}
