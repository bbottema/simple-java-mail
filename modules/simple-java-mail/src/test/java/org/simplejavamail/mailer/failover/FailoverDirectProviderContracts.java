package org.simplejavamail.mailer.failover;

import jakarta.mail.MessagingException;
import org.junit.jupiter.api.Test;
import org.simplejavamail.api.mailer.MailSendOutcome;
import org.simplejavamail.api.mailer.Mailer;
import testutil.smtp.RecordingSmtpServer;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;
import static org.simplejavamail.api.mailer.MailSubmissionStatus.ACCEPTED;
import static org.simplejavamail.mailer.failover.FailoverContractSupport.*;

/** Selected explicitly in the no-pool JVM: ordinary direct sends close the socket before publishing their outcome. */
class FailoverDirectProviderContracts {
    @Test
    void acceptanceThenCloseFailureRetainsTheReceiptWithoutReplaying() throws Exception {
        assertThat(DIRECT).isTrue();
        final FailoverTestProvider.State state = new FailoverTestProvider.State();
        state.cleanupFailure = new MessagingException("close failed after acceptance");
        final List<MailSendOutcome> outcomes = new CopyOnWriteArrayList<>();
        try (RecordingSmtpServer secondary = new RecordingSmtpServer(); Mailer selected = builder(secondary.port()).buildMailer();
             Mailer initiating = FailoverTestProvider.builder(state).withFailoverMailer(selected, policy()).withMailSendObserver(outcomes::add).buildMailer()) {
            final Throwable failure = catchThrowable(() -> initiating.sync().sendMail(email()));
            assertThat(failure).hasRootCause(state.cleanupFailure);
            assertThat(outcomes).hasSize(1);
            assertThat(outcomes.get(0).getFailure()).containsSame(failure);
            assertThat(outcomes.get(0).isSuccessful()).isFalse();
            assertThat(outcomes.get(0).getSubmissionReceipt().orElseThrow().getStatus()).isEqualTo(ACCEPTED);
            assertThat(state.invocations).hasValue(1);
            assertThat(secondary.connectionCount()).isZero();
        }
    }
}
