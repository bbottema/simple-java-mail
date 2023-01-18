package org.simplejavamail.mailer;

import jakarta.mail.Session;
import jakarta.mail.internet.MimeMessage;
import org.jetbrains.annotations.NotNull;
import org.junit.Before;
import org.junit.Test;
import org.simplejavamail.api.email.Email;
import org.simplejavamail.api.mailer.CustomMailer;
import org.simplejavamail.api.mailer.config.OperationalConfig;
import org.simplejavamail.email.EmailBuilder;
import testutil.ConfigLoaderTestHelper;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;

public class ResultHandlingTest {

	@Before
	public void setup() {
		ConfigLoaderTestHelper.clearConfigProperties();
	}

	@Test
	public void emailSentSuccesfullyShouldInvokeOnSuccessHandler() throws Exception {
		emailSentSuccesfullyShouldInvokeOnSuccessHandler(sendAsyncMailUsingMailerAPI(true));
		emailSentSuccesfullyShouldInvokeOnSuccessHandler(sendAsyncMailUsingMailerBuilderAPI(true));
	}

	private void emailSentSuccesfullyShouldInvokeOnSuccessHandler(CompletableFuture<Void> f) throws InterruptedException, ExecutionException {
		// set handlers, then wait for result
		final AtomicReference<Boolean> successHandlerInvoked = new AtomicReference<>(false);
		final AtomicReference<Boolean> exceptionHandlerInvoked = new AtomicReference<>(false);

		f.whenComplete((unused, e) -> (e != null ? exceptionHandlerInvoked : successHandlerInvoked).set(true));
		f.get();

		assertThat(successHandlerInvoked).hasValue(true);
		assertThat(exceptionHandlerInvoked).hasValue(false);
	}

	@Test
	public void emailSentSuccesfullyShouldInvokeOnSuccessHandlerAfterDelay() throws Exception {
		emailSentSuccesfullyShouldInvokeOnSuccessHandlerAfterDelay(sendAsyncMailUsingMailerAPI(true));
		emailSentSuccesfullyShouldInvokeOnSuccessHandlerAfterDelay(sendAsyncMailUsingMailerBuilderAPI(true));
	}

	private void emailSentSuccesfullyShouldInvokeOnSuccessHandlerAfterDelay(CompletableFuture<Void> f) throws InterruptedException, ExecutionException {
		// wait for result, then set handlers
		f.get();
		final AtomicReference<Boolean> successHandlerInvoked = new AtomicReference<>(false);
		final AtomicReference<Boolean> exceptionHandlerInvoked = new AtomicReference<>(false);

		f.whenComplete((unused, e) -> (e != null ? exceptionHandlerInvoked : successHandlerInvoked).set(true));

		assertThat(successHandlerInvoked).hasValue(true);
		assertThat(exceptionHandlerInvoked).hasValue(false);
	}

	@Test
	public void emailSentSuccesfullyShouldInvokeOnExceptionHandler() {
		emailSentSuccesfullyShouldInvokeOnExceptionHandler(sendAsyncMailUsingMailerAPI(false));
		emailSentSuccesfullyShouldInvokeOnExceptionHandler(sendAsyncMailUsingMailerBuilderAPI(false));
	}

	private void emailSentSuccesfullyShouldInvokeOnExceptionHandler(CompletableFuture<Void> f) {
		// set handlers, then wait for result
		final AtomicReference<Boolean> successHandlerInvoked = new AtomicReference<>(false);
		final AtomicReference<Boolean> exceptionHandlerInvoked = new AtomicReference<>(false);

		f.whenComplete((unused, e) -> (e != null ? exceptionHandlerInvoked : successHandlerInvoked).set(true));

		try {
			f.get();
		} catch (Exception e) {
			// good
		}

		assertThat(successHandlerInvoked).hasValue(false);
		assertThat(exceptionHandlerInvoked).hasValue(true);
	}

	@Test
	public void emailSentSuccesfullyShouldInvokeOnExceptionHandlerAfterDelay() {
		CompletableFuture<Void> f = sendAsyncMailUsingMailerAPI(false);

		// wait for result, then set handlers
		try {
			f.get();
		} catch (Exception e) {
			// good
		}
		final AtomicReference<Boolean> successHandlerInvoked = new AtomicReference<>(false);
		final AtomicReference<Boolean> exceptionHandlerInvoked = new AtomicReference<>(false);

		f.whenComplete((unused, e) -> (e != null ? exceptionHandlerInvoked : successHandlerInvoked).set(true));

		assertThat(successHandlerInvoked).hasValue(false);
		assertThat(exceptionHandlerInvoked).hasValue(true);
	}

	@NotNull
	private CompletableFuture<Void> sendAsyncMailUsingMailerAPI(boolean sendSuccesfully) {
		final boolean async = true;
		return MailerBuilder
				.withSMTPServer("localhost", 0)
				.withCustomMailer(new MySimulatingMailer(sendSuccesfully))
				.buildMailer().sendMail(EmailBuilder.startingBlank()
						.to("a@b.com")
						.from("Simple Java Mail demo", "simplejavamail@demo.app")
						.withPlainText("")
						.buildEmail(), async);
	}

	@NotNull
	private CompletableFuture<Void> sendAsyncMailUsingMailerBuilderAPI(boolean sendSuccesfully) {
		return MailerBuilder
				.withSMTPServer("localhost", 0)
				.withCustomMailer(new MySimulatingMailer(sendSuccesfully))
				.async()
				.buildMailer().sendMail(EmailBuilder.startingBlank()
						.to("a@b.com")
						.from("Simple Java Mail demo", "simplejavamail@demo.app")
						.withPlainText("")
						.buildEmail());
	}

	private static class MySimulatingMailer implements CustomMailer {
		final boolean shouldSendSuccesfully;

		private MySimulatingMailer(final boolean shouldSendSuccesfully) {
			this.shouldSendSuccesfully = shouldSendSuccesfully;
		}

		@Override
		public void testConnection(@NotNull final OperationalConfig operationalConfig, @NotNull final Session session) {
		}

		@Override
		public void sendMessage(@NotNull OperationalConfig operationalConfig, @NotNull Session session, @NotNull Email email, @NotNull MimeMessage message) {
			if (!shouldSendSuccesfully) {
				throw new RuntimeException("simulating fail...");
			}
		}
	}
}