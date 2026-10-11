package org.simplejavamail.mailer.internal;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.simplejavamail.api.email.Email;
import org.simplejavamail.api.mailer.MailSender;
import org.simplejavamail.api.mailer.MailSubmissionReceipt;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;

@Timeout(10)
class ScopedMailSenderTest {

	@Test
	void callbackThreadDelegatesBothMethodsAndPreservesReceiptIdentity() {
		final MailSender delegate = mock(MailSender.class);
		final Email email = mock(Email.class);
		final MailSubmissionReceipt receipt = mock(MailSubmissionReceipt.class);
		when(delegate.sendMailAndGetReceipt(email)).thenReturn(receipt);
		final ScopedMailSender sender = new ScopedMailSender(delegate);
		try {
			sender.sendMail(email);
			assertThat(sender.sendMailAndGetReceipt(email)).isSameAs(receipt);
			verify(delegate).sendMail(email);
			verify(delegate).sendMailAndGetReceipt(email);
			verifyNoMoreInteractions(delegate);
		} finally {
			sender.endCallback();
		}
	}

	@Test
	void anotherThreadCannotSendOrEndTheCallback() throws Exception {
		final MailSender delegate = mock(MailSender.class);
		final ScopedMailSender sender = new ScopedMailSender(delegate);
		final ExecutorService worker = Executors.newSingleThreadExecutor();
		try {
			worker.submit(() -> {
				assertThatThrownBy(() -> sender.sendMail(null)).isInstanceOf(IllegalStateException.class).hasMessageContaining("thread running its callback");
				assertThatThrownBy(() -> sender.sendMailAndGetReceipt(null)).isInstanceOf(IllegalStateException.class)
						.hasMessageContaining("thread running its callback");
				assertThatThrownBy(sender::endCallback).isInstanceOf(IllegalStateException.class).hasMessageContaining("thread running its callback");
			}).get(5, TimeUnit.SECONDS);
			verifyNoInteractions(delegate);
			// A rejected foreign-thread call must not disable legitimate callback-thread use.
			sender.sendMail(null);
			verify(delegate).sendMail(null);
		} finally {
			sender.endCallback();
			worker.shutdownNow();
			assertThat(worker.awaitTermination(5, TimeUnit.SECONDS)).isTrue();
		}
	}

	@Test
	void endedCallbackRejectsBothMethodsBeforeDelegation() {
		final MailSender delegate = mock(MailSender.class);
		final ScopedMailSender sender = new ScopedMailSender(delegate);
		sender.endCallback();
		sender.endCallback();
		assertThatThrownBy(() -> sender.sendMail(null)).isInstanceOf(IllegalStateException.class).hasMessageContaining("callback has ended");
		assertThatThrownBy(() -> sender.sendMailAndGetReceipt(null)).isInstanceOf(IllegalStateException.class).hasMessageContaining("callback has ended");
		verifyNoInteractions(delegate);
	}
}
