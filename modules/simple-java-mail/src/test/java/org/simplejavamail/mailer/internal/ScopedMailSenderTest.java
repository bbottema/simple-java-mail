package org.simplejavamail.mailer.internal;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.simplejavamail.api.email.Email;
import org.simplejavamail.api.mailer.MailSender;
import org.simplejavamail.api.mailer.MailRetryDisposition;
import org.simplejavamail.api.mailer.MailSubmissionReceipt;
import org.simplejavamail.api.mailer.MailSubmissionStatus;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@Timeout(10)
class ScopedMailSenderTest {

	@Test
	void callbackThreadCanIgnoreOrRetainTheExactReceipt() {
		final RecordingSender delegate = new RecordingSender();
		final ScopedMailSender sender = new ScopedMailSender(delegate);
		try {
			// The scope guard delegates argument validation; this fixture needs no MIME/provider or mocking-framework bootstrap.
			sender.sendMail(null);
			assertThat(sender.sendMail(null)).isSameAs(delegate.receipt);
			assertThat(delegate.emails).containsExactly(null, null);
		} finally {
			sender.endCallback();
		}
	}

	@Test
	void anotherThreadCannotSendOrEndTheCallback() throws Exception {
		final RecordingSender delegate = new RecordingSender();
		final ScopedMailSender sender = new ScopedMailSender(delegate);
		final ExecutorService worker = Executors.newSingleThreadExecutor();
		try {
			worker.submit(() -> {
				assertThatThrownBy(() -> sender.sendMail(null)).isInstanceOf(IllegalStateException.class).hasMessageContaining("thread running its callback");
				assertThatThrownBy(sender::endCallback).isInstanceOf(IllegalStateException.class).hasMessageContaining("thread running its callback");
			}).get(5, TimeUnit.SECONDS);
			assertThat(delegate.emails).isEmpty();
			// A rejected foreign-thread call must not disable legitimate callback-thread use.
			sender.sendMail(null);
			assertThat(delegate.emails).containsExactly((Email) null);
		} finally {
			sender.endCallback();
			worker.shutdownNow();
			assertThat(worker.awaitTermination(5, TimeUnit.SECONDS)).isTrue();
		}
	}

	@Test
	void endedCallbackRejectsSendingBeforeDelegation() {
		final RecordingSender delegate = new RecordingSender();
		final ScopedMailSender sender = new ScopedMailSender(delegate);
		sender.endCallback();
		sender.endCallback();
		assertThatThrownBy(() -> sender.sendMail(null)).isInstanceOf(IllegalStateException.class).hasMessageContaining("callback has ended");
		assertThat(delegate.emails).isEmpty();
	}

	private static final class RecordingSender implements MailSender {
		private final List<Email> emails = new ArrayList<>();
		private final MailSubmissionReceipt receipt = MailSubmissionReceiptImpl.builder()
				.submittedAt(Instant.EPOCH)
				.status(MailSubmissionStatus.UNKNOWN)
				.recipientResults(List.of())
				.retryDisposition(MailRetryDisposition.CALLER_POLICY_REQUIRED)
				.build();

		@Override
		public MailSubmissionReceipt sendMail(final Email email) {
			emails.add(email);
			return receipt;
		}
	}
}
