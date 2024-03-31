package org.simplejavamail.mailer;

import jakarta.mail.Header;
import jakarta.mail.Session;
import jakarta.mail.internet.MimeMessage;
import lombok.SneakyThrows;
import lombok.val;
import org.apache.poi.ss.formula.functions.T;
import org.jetbrains.annotations.NotNull;
import org.junit.Before;
import org.junit.Test;
import org.simplejavamail.api.email.Email;
import org.simplejavamail.api.email.config.DkimConfig;
import org.simplejavamail.api.mailer.CustomMailer;
import org.simplejavamail.api.mailer.Mailer;
import org.simplejavamail.api.mailer.config.OperationalConfig;
import org.simplejavamail.converter.EmailConverter;
import org.simplejavamail.email.EmailBuilder;
import testutil.ConfigLoaderTestHelper;

import java.io.ByteArrayInputStream;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Collection;
import java.util.Collections;
import java.util.Enumeration;
import java.util.List;
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

	@Test
	public void emailSentUsingCustomMailerShouldProvideDkimHeader() throws Exception {
		String privateDERkeyBase64 =
				"MIICdgIBADANBgkqhkiG9w0BAQEFAASCAmAwggJcAgEAAoGBAMYuC7ZjFBSWJtP6JH8w1deJE+5sLwkUacZcW4MTVQXTM33BzN8Ec64KO1Hk2B9oxkpdunKt"
						+ "BggwbWMlGU5gGu4PpQ20cdPcfBIkUMlQKaakHPPGNYaF9dQaZIRy8XON6g1sOJGALXtUYX1r5hdDH13kC/YBw9f1Dsi2smrB0qabAgMBAAECgYAdWbBuYJoWum4hssg49hiVhT2ob+k"
						+ "/ZQCNWhxLe096P18+3rbiyJwBSI6kgEnpzPChDuSQG0PrbpCkwFfRHbafDIPiMi5b6YZkJoFmmOmBHsewS1VdR/phk+aPQV2SoJ0S0FAGZkOnOkagHfmEMSgjZzTpJouu5NU8mwqz8z"
						+ "/s0QJBAOUnELTMG/Se3Pw4FQ49K49lA81QaMoL63lYIEvc6uSVoJSEcrBFxv5sfJW2LFWs8VIDyTvYzsCjLwZj6nwA3k0CQQDdZgVHX7crlpUxO/cjKtTa/Nq9S6XLv3S6XX3YJJ9/Z"
						+ "pYpqAWJbbR+8scBgVxS+9NLLeHhlx/EvkaZRdLhwRyHAkEAtr1ThkqrFIXHxt9Wczd20HCG+qlgF5gv3WHYx4bSTx2/pBCHgWjzyxtqst1HN7+l5nicdrxsDJVVv+vYJ7FtlQJAWPgG"
						+ "Zwgvs3Rvv7k5NwifQOEbhbZAigAGCF5Jk/Ijpi6zaUn7754GSn2FOzWgxDguUKe/fcgdHBLai/1jIRVZQQJAXF2xzWMwP+TmX44QxK52QHVI8mhNzcnH7A311gWns6AbLcuLA9quwjU"
						+ "YJMRlfXk67lJXCleZL15EpVPrQ34KlA==";

		final MimeMessageExtractingMailer mimeMessageExtractingMailer = new MimeMessageExtractingMailer();

		try (Mailer mailer = MailerBuilder
                .withSMTPServer("localhost", 0)
                .withCustomMailer(mimeMessageExtractingMailer)
                .buildMailer()) {

			final Email dkimMail = EmailBuilder.startingBlank()
					.to("a@b.com")
					.from("Simple Java Mail demo", "simplejavamail@supersecret-testing-domain.com")
					.withSubject("test")
					.withPlainText("")
					.signWithDomainKey(DkimConfig.builder()
							.dkimPrivateKeyData(new ByteArrayInputStream(Base64.getDecoder().decode(privateDERkeyBase64)))
							.dkimSigningDomain("supersecret-testing-domain.com")
							.dkimSelector("dkim1")
							.excludedHeadersFromDkimDefaultSigningList("Reply-To")
							.build())
					.buildEmail();

			final CompletableFuture<Void> f = mailer.sendMail(dkimMail);

			f.get();

			System.out.println("-----------------------");
			System.out.println("Headers:");
			final MimeMessage message = mimeMessageExtractingMailer.message;
			final String s = EmailConverter.mimeMessageToEML(message);
			final MimeMessage mimeMessage = EmailConverter.emlToMimeMessage(s);
			for (Header header : Collections.list(mimeMessage.getAllHeaders())) {
				System.out.println(header.getName() + ": " + header.getValue());
			}
			System.out.println("-----------------------");
        }
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

	private static class MimeMessageExtractingMailer implements CustomMailer {

		private MimeMessage message;

		@Override
		public void testConnection(@NotNull final OperationalConfig operationalConfig, @NotNull final Session session) {
		}

		@SneakyThrows
		@Override
		public void sendMessage(@NotNull OperationalConfig operationalConfig, @NotNull Session session, @NotNull Email email, @NotNull MimeMessage message) {
			this.message = message;
		}
	}
}