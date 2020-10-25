package org.simplejavamail.mailer;

import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.junit.Before;
import org.junit.Test;
import org.simplejavamail.api.email.Email;
import org.simplejavamail.api.mailer.AsyncResponse;
import org.simplejavamail.api.mailer.CustomMailer;
import org.simplejavamail.api.mailer.config.OperationalConfig;
import org.simplejavamail.email.EmailBuilder;
import testutil.ConfigLoaderTestHelper;

import javax.mail.Session;
import javax.mail.internet.MimeMessage;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;

public class ResultHandlingTest {

	@Before
	public void setup() {
		ConfigLoaderTestHelper.clearConfigProperties();
	}

	@Test
	public void emailSentSuccesfullyShouldInvokeOnSuccessHandler() throws Exception {
		AsyncResponse asyncResponse = sendAsyncMail(true);

		// set handlers, then wait for result
		final AtomicReference<Boolean> successHandlerInvoked = new AtomicReference<>(false);
		final AtomicReference<Boolean> exceptionHandlerInvoked = new AtomicReference<>(false);
		asyncResponse.onSuccess(new Runnable() {
			@Override
			public void run() {
				successHandlerInvoked.set(true);
			}
		});
		asyncResponse.onException(new AsyncResponse.ExceptionConsumer() {
			@Override
			public void accept(final Exception e) {
				exceptionHandlerInvoked.set(true);
			}
		});
		asyncResponse.getFuture().get();

		assertThat(successHandlerInvoked).hasValue(true);
		assertThat(exceptionHandlerInvoked).hasValue(false);
	}

	@Test
	public void emailSentSuccesfullyShouldInvokeOnSuccessHandlerAfterDelay() throws Exception {
		AsyncResponse asyncResponse = sendAsyncMail(true);

		// wait for result, then set handlers
		asyncResponse.getFuture().get();
		final AtomicReference<Boolean> successHandlerInvoked = new AtomicReference<>(false);
		final AtomicReference<Boolean> exceptionHandlerInvoked = new AtomicReference<>(false);
		asyncResponse.onSuccess(new Runnable() {
			@Override
			public void run() {
				successHandlerInvoked.set(true);
			}
		});
		asyncResponse.onException(new AsyncResponse.ExceptionConsumer() {
			@Override
			public void accept(final Exception e) {
				exceptionHandlerInvoked.set(true);
			}
		});

		assertThat(successHandlerInvoked).hasValue(true);
		assertThat(exceptionHandlerInvoked).hasValue(false);
	}

	@Test
	public void emailSentSuccesfullyShouldInvokeOnExceptionHandler() throws Exception {
		AsyncResponse asyncResponse = sendAsyncMail(false);

		// set handlers, then wait for result
		final AtomicReference<Boolean> successHandlerInvoked = new AtomicReference<>(false);
		final AtomicReference<Boolean> exceptionHandlerInvoked = new AtomicReference<>(false);
		asyncResponse.onSuccess(new Runnable() {
			@Override
			public void run() {
				successHandlerInvoked.set(true);
			}
		});
		asyncResponse.onException(new AsyncResponse.ExceptionConsumer() {
			@Override
			public void accept(final Exception e) {
				exceptionHandlerInvoked.set(true);
			}
		});

		try {
			asyncResponse.getFuture().get();
		} catch (Exception e) {
			// good
		}

		assertThat(successHandlerInvoked).hasValue(false);
		assertThat(exceptionHandlerInvoked).hasValue(true);
	}

	@Test
	public void emailSentSuccesfullyShouldInvokeOnExceptionHandlerAfterDelay() throws Exception {
		AsyncResponse asyncResponse = sendAsyncMail(false);

		// wait for result, then set handlers
		try {
			asyncResponse.getFuture().get();
		} catch (Exception e) {
			// good
		}
		final AtomicReference<Boolean> successHandlerInvoked = new AtomicReference<>(false);
		final AtomicReference<Boolean> exceptionHandlerInvoked = new AtomicReference<>(false);
		asyncResponse.onSuccess(new Runnable() {
			@Override
			public void run() {
				successHandlerInvoked.set(true);
			}
		});
		asyncResponse.onException(new AsyncResponse.ExceptionConsumer() {
			@Override
			public void accept(final Exception e) {
				exceptionHandlerInvoked.set(true);
			}
		});

		assertThat(successHandlerInvoked).hasValue(false);
		assertThat(exceptionHandlerInvoked).hasValue(true);
	}

	@Nullable
	private AsyncResponse sendAsyncMail(boolean sendSuccesfully) {
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

	private static class MySimulatingMailer implements CustomMailer {
		final boolean shouldSendSuccesfully;

		private MySimulatingMailer(final boolean shouldSendSuccesfully) {
			this.shouldSendSuccesfully = shouldSendSuccesfully;
		}

		@Override
		public void testConnection(@NotNull final OperationalConfig operationalConfig, @NotNull final Session session) {
		}

		@Override
		public void sendMessage(@NotNull final OperationalConfig operationalConfig, @NotNull final Session session, final Email email, @NotNull final MimeMessage message) {
			if (!shouldSendSuccesfully) {
				throw new RuntimeException("simulating fail...");
			}
		}
	}
}