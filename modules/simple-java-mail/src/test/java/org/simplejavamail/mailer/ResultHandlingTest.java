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
	
	private void emailSentSuccesfullyShouldInvokeOnSuccessHandler(AsyncResponse asyncResponse) throws InterruptedException, ExecutionException {
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
		emailSentSuccesfullyShouldInvokeOnSuccessHandlerAfterDelay(sendAsyncMailUsingMailerAPI(true));
		emailSentSuccesfullyShouldInvokeOnSuccessHandlerAfterDelay(sendAsyncMailUsingMailerBuilderAPI(true));
	}
	
	private void emailSentSuccesfullyShouldInvokeOnSuccessHandlerAfterDelay(AsyncResponse asyncResponse) throws InterruptedException, ExecutionException {
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
	public void emailSentSuccesfullyShouldInvokeOnExceptionHandler() {
		emailSentSuccesfullyShouldInvokeOnExceptionHandler(sendAsyncMailUsingMailerAPI(false));
		emailSentSuccesfullyShouldInvokeOnExceptionHandler(sendAsyncMailUsingMailerBuilderAPI(false));
	}
	
	private void emailSentSuccesfullyShouldInvokeOnExceptionHandler(AsyncResponse asyncResponse) {
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
		AsyncResponse asyncResponse = sendAsyncMailUsingMailerAPI(false);

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
	private AsyncResponse sendAsyncMailUsingMailerAPI(boolean sendSuccesfully) {
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
	
	@Nullable
	private AsyncResponse sendAsyncMailUsingMailerBuilderAPI(boolean sendSuccesfully) {
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
		public void sendMessage(@NotNull final OperationalConfig operationalConfig, @NotNull final Session session, final Email email, @NotNull final MimeMessage message) {
			if (!shouldSendSuccesfully) {
				throw new RuntimeException("simulating fail...");
			}
		}
	}
}