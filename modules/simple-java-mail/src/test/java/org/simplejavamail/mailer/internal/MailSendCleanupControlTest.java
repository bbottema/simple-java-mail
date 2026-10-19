package org.simplejavamail.mailer.internal;

import jakarta.mail.MessagingException;
import org.junit.jupiter.api.Test;
import org.simplejavamail.api.mailer.MailSendCancelledException;
import org.simplejavamail.api.mailer.MailSendCleanupException;
import org.simplejavamail.api.mailer.MailSendException;
import org.simplejavamail.api.mailer.MailSubmissionException;
import org.simplejavamail.api.mailer.MailSubmissionReceipt;
import org.simplejavamail.api.mailer.config.OperationalConfig;

import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class MailSendCleanupControlTest {

	@Test
	void cancellationDuringCleanupKeepsTheLatestAcceptanceInsteadOfAnEarlierRetryRefusal() {
		final MailSubmissionReceipt earlierRefusal = mock(MailSubmissionReceipt.class);
		final MailSubmissionReceipt accepted = mock(MailSubmissionReceipt.class);
		final MailSubmissionException refusal = new MailSubmissionException("temporary refusal", new MessagingException("451"), earlierRefusal);
		final MailSendCleanupException cleanup = new MailSendCleanupException("cleanup failed", new IllegalStateException("release"), accepted);
		final OperationalConfig config = mock(OperationalConfig.class);
		when(config.getMailSendTimeout()).thenReturn(null);
		final MailSendOperations operations = new MailSendOperations(config);
		final AtomicReference<Throwable> outcomeFailure = new AtomicReference<>();
		final MailSendOperation<MailSubmissionReceipt> operation = operations.begin(unused -> { }, outcomeFailure::set);
		@SuppressWarnings("unchecked")
		final MailSendOperation.RetryingWork<MailSubmissionReceipt> retrying = mock(MailSendOperation.RetryingWork.class);
		when(retrying.lastFailure()).thenReturn(refusal);
		when(retrying.execute()).thenAnswer(invocation -> {
			operation.control().requestCancellation();
			throw operation.control().translateFailure(cleanup);
		});
		try {
			final Throwable failure = catchThrowable(() -> operation.executeSync(() -> operation.executeRetrying(retrying)));
			assertThat(failure).isInstanceOf(MailSendCancelledException.class).hasCause(cleanup);
			assertThat(((MailSendException) failure).getSubmissionReceipt()).containsSame(accepted);
			assertThat(outcomeFailure).hasValue(failure);
		} finally {
			operations.shutdown().join();
		}
	}
}
