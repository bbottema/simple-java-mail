package org.simplejavamail.mailer;

import jakarta.mail.Header;
import jakarta.mail.Session;
import jakarta.mail.internet.MimeMessage;
import lombok.SneakyThrows;
import lombok.val;
import org.apache.logging.log4j.Level;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.core.LogEvent;
import org.apache.logging.log4j.core.appender.AbstractAppender;
import org.apache.logging.log4j.core.config.Property;
import org.apache.poi.ss.formula.functions.T;
import org.jetbrains.annotations.NotNull;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.MockedStatic;
import org.mockito.Mockito;
import org.simplejavamail.api.email.Email;
import org.simplejavamail.api.email.config.DkimConfig;
import org.simplejavamail.api.mailer.CustomMailer;
import org.simplejavamail.api.mailer.Mailer;
import org.simplejavamail.api.mailer.config.OperationalConfig;
import org.simplejavamail.converter.EmailConverter;
import org.simplejavamail.email.EmailBuilder;
import org.simplejavamail.internal.util.concurrent.NamedRunnable;
import org.simplejavamail.internal.moduleloader.ModuleLoader;
import org.simplejavamail.mailer.internal.AbstractProxyServerSyncingClosure;
import testutil.ConfigLoaderTestHelper;

import java.io.ByteArrayInputStream;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Collection;
import java.util.Collections;
import java.util.Enumeration;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.never;

public class ResultHandlingTest {

	@BeforeEach
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
		final DkimConfig defaultDkimConfig = DkimConfig.builder()
				.dkimPrivateKeyData(new ByteArrayInputStream(Base64.getDecoder().decode(privateDERkeyBase64)))
				.dkimSigningDomain("supersecret-testing-domain.com")
				.dkimSelector("dkim1")
				.excludedHeadersFromDkimDefaultSigningList("Reply-To")
				.build();

		try (Mailer mailer = MailerBuilder
                .withSMTPServer("localhost", 0)
				.withDefaultDkimSigning(defaultDkimConfig)
                .withCustomMailer(mimeMessageExtractingMailer)
                .buildMailer()) {

			final Email dkimMail = EmailBuilder.startingBlank()
					.to("a@b.com")
					.from("Simple Java Mail demo", "simplejavamail@supersecret-testing-domain.com")
					.withSubject("test")
					.withPlainText("")
					.buildEmail();

			final CompletableFuture<Void> f = mailer.sendMail(dkimMail);

			f.get();

			System.out.println("-----------------------");
			System.out.println("Headers:");
			final MimeMessage message = mimeMessageExtractingMailer.message;
			final String s = EmailConverter.mimeMessageToEML(message);
			final MimeMessage mimeMessage = EmailConverter.emlToMimeMessage(s);
			assertThat(mimeMessage.getHeader("DKIM-Signature")).hasSize(1);
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

	@Test
	public void asyncSendFailureShouldCompleteFutureWithoutFrameworkErrorLog() {
		try (ErrorLogCaptor errorLogCaptor = ErrorLogCaptor.forLogger(NamedRunnable.class.getName())) {
			final CompletableFuture<Void> f = sendAsyncMailUsingMailerAPI(false);

			assertThatThrownBy(f::get).isInstanceOf(ExecutionException.class);

			assertThat(errorLogCaptor.errorEvents()).isEmpty();
		}
	}

	@Test
	public void asyncSendFailureShouldCompleteFutureWithoutFrameworkErrorLogWithoutBatchModule() {
		try (MockedStatic<ModuleLoader> moduleLoaderMockedStatic = mockStatic(ModuleLoader.class, Mockito.CALLS_REAL_METHODS);
			 ErrorLogCaptor errorLogCaptor = ErrorLogCaptor.forLogger(NamedRunnable.class.getName())) {
			moduleLoaderMockedStatic.when(ModuleLoader::batchModuleAvailable).thenReturn(false);

			final CompletableFuture<Void> f = sendAsyncMailUsingMailerAPI(false);

			assertThatThrownBy(f::get).isInstanceOf(ExecutionException.class);
			assertThat(errorLogCaptor.errorEvents()).isEmpty();
			moduleLoaderMockedStatic.verify(ModuleLoader::loadBatchModule, never());
		}
	}

	@Test
	public void asyncTestConnectionFailureShouldCompleteFutureWithoutFrameworkErrorLog() throws Exception {
		try (ErrorLogCaptor errorLogCaptor = ErrorLogCaptor.forLogger(AbstractProxyServerSyncingClosure.class.getName());
			 Mailer mailer = MailerBuilder
					 .withSMTPServer("localhost", 0)
					 .withCustomMailer(new FailingConnectionTestMailer())
					 .async()
					 .buildMailer()) {
			final CompletableFuture<Void> f = mailer.testConnection(true);

			assertThatThrownBy(f::get).isInstanceOf(ExecutionException.class);

			assertThat(errorLogCaptor.errorEvents()).isEmpty();
		}
	}

	@Test
	public void testConnectionShouldUseBuilderAsyncDefault() throws Exception {
		final BlockingTestConnectionMailer blockingMailer = new BlockingTestConnectionMailer();
		final Mailer mailer = MailerBuilder
				.withSMTPServer("localhost", 0)
				.withCustomMailer(blockingMailer)
				.async()
				.buildMailer();

		assertNoArgTestConnectionReturnsBeforeCustomConnectionTestCompletes(mailer, blockingMailer);
	}

	@Test
	public void testConnectionShouldUseBuiltInAsyncFallbackWithoutBatchModule() throws Exception {
		try (MockedStatic<ModuleLoader> moduleLoaderMockedStatic = mockStatic(ModuleLoader.class, Mockito.CALLS_REAL_METHODS)) {
			moduleLoaderMockedStatic.when(ModuleLoader::batchModuleAvailable).thenReturn(false);

			final BlockingTestConnectionMailer blockingMailer = new BlockingTestConnectionMailer();
			final Mailer mailer = MailerBuilder
					.withSMTPServer("localhost", 0)
					.withCustomMailer(blockingMailer)
					.async()
					.buildMailer();

			assertNoArgTestConnectionReturnsBeforeCustomConnectionTestCompletes(mailer, blockingMailer);

			moduleLoaderMockedStatic.verify(ModuleLoader::loadBatchModule, never());
		}
	}

	private void assertNoArgTestConnectionReturnsBeforeCustomConnectionTestCompletes(final Mailer mailer, final BlockingTestConnectionMailer blockingMailer) throws Exception {
		final ExecutorService caller = Executors.newSingleThreadExecutor();
		Future<?> testConnectionCall = null;

		try {
			testConnectionCall = caller.submit(new Runnable() {
				@Override
				public void run() {
					mailer.testConnection();
				}
			});

			assertThat(blockingMailer.awaitConnectionTestStarted()).isTrue();
			assertThat(testConnectionCall.get(250, TimeUnit.MILLISECONDS)).isNull();
		} finally {
			blockingMailer.releaseConnectionTest();
			if (testConnectionCall != null) {
				testConnectionCall.get(2, TimeUnit.SECONDS);
			}
			caller.shutdownNow();
			mailer.close();
		}
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

	private static class FailingConnectionTestMailer implements CustomMailer {

		@Override
		public void testConnection(@NotNull final OperationalConfig operationalConfig, @NotNull final Session session) {
			throw new RuntimeException("simulating connection test fail...");
		}

		@Override
		public void sendMessage(@NotNull OperationalConfig operationalConfig, @NotNull Session session, @NotNull Email email, @NotNull MimeMessage message) {
		}
	}

	private static class BlockingTestConnectionMailer implements CustomMailer {
		private final CountDownLatch connectionTestStarted = new CountDownLatch(1);
		private final CountDownLatch releaseConnectionTest = new CountDownLatch(1);

		boolean awaitConnectionTestStarted() throws InterruptedException {
			return connectionTestStarted.await(2, TimeUnit.SECONDS);
		}

		void releaseConnectionTest() {
			releaseConnectionTest.countDown();
		}

		@Override
		public void testConnection(@NotNull final OperationalConfig operationalConfig, @NotNull final Session session) {
			connectionTestStarted.countDown();
			try {
				if (!releaseConnectionTest.await(5, TimeUnit.SECONDS)) {
					throw new AssertionError("Timed out waiting to release blocked connection test");
				}
			} catch (InterruptedException e) {
				Thread.currentThread().interrupt();
				throw new RuntimeException(e);
			}
		}

		@Override
		public void sendMessage(@NotNull OperationalConfig operationalConfig, @NotNull Session session, @NotNull Email email, @NotNull MimeMessage message) {
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

	private static class ErrorLogCaptor implements AutoCloseable {

		@NotNull private final org.apache.logging.log4j.core.Logger logger;
		@NotNull private final CapturingAppender appender;

		private ErrorLogCaptor(@NotNull final String loggerName) {
			this.logger = (org.apache.logging.log4j.core.Logger) LogManager.getLogger(loggerName);
			this.appender = new CapturingAppender("capturing-appender-" + loggerName);
			this.appender.start();
			this.logger.addAppender(appender);
		}

		@NotNull
		private static ErrorLogCaptor forLogger(@NotNull final String loggerName) {
			return new ErrorLogCaptor(loggerName);
		}

		@NotNull
		private List<LogEvent> errorEvents() {
			return appender.events.stream()
					.filter(event -> event.getLevel().isMoreSpecificThan(Level.ERROR))
					.collect(Collectors.toList());
		}

		@Override
		public void close() {
			logger.removeAppender(appender);
			appender.stop();
		}
	}

	private static class CapturingAppender extends AbstractAppender {

		@NotNull private final List<LogEvent> events = new CopyOnWriteArrayList<>();

		private CapturingAppender(@NotNull final String name) {
			super(name, null, null, true, Property.EMPTY_ARRAY);
		}

		@Override
		public void append(@NotNull final LogEvent event) {
			events.add(event.toImmutable());
		}
	}
}
