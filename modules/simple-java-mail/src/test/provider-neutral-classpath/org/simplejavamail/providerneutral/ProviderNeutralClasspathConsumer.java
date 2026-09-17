package org.simplejavamail.providerneutral;

import jakarta.mail.Address;
import jakarta.mail.Message;
import jakarta.mail.MessagingException;
import jakarta.mail.Session;
import jakarta.mail.Transport;
import jakarta.mail.URLName;
import jakarta.mail.internet.InternetAddress;
import org.simplejavamail.api.SimpleJavaMail;
import org.simplejavamail.api.email.Email;
import org.simplejavamail.api.email.EmailPopulatingBuilder;
import org.simplejavamail.api.email.ExactEmailBuilder;
import org.simplejavamail.api.email.Recipient;
import org.simplejavamail.api.mailer.spi.DeliveryRecipient;
import org.simplejavamail.recipient.RecipientBuilder;
import org.simplejavamail.recipient.RecipientsBuilder;
import org.simplejavamail.api.email.config.DkimConfig;
import org.simplejavamail.api.email.config.DeliveryStatusNotification;
import org.simplejavamail.api.email.config.SmimeEncryptionConfig;
import org.simplejavamail.api.email.config.SmimeSigningConfig;
import org.simplejavamail.api.mailer.AsyncQueueRejectionReason;
import org.simplejavamail.api.mailer.AsyncQueueSnapshot;
import org.simplejavamail.api.mailer.MailRecipientDisposition;
import org.simplejavamail.api.mailer.MailRecipientResult;
import org.simplejavamail.api.mailer.MailRetryDisposition;
import org.simplejavamail.api.mailer.MailSend;
import org.simplejavamail.api.mailer.MailSendCancelledException;
import org.simplejavamail.api.mailer.MailSendObserver;
import org.simplejavamail.api.mailer.MailSendRejectedException;
import org.simplejavamail.api.mailer.MailSendTimeoutException;
import org.simplejavamail.api.mailer.MailSubmissionReceipt;
import org.simplejavamail.api.mailer.MailSubmissionStatus;
import org.simplejavamail.api.mailer.Mailer;
import org.simplejavamail.api.mailer.SmtpCapabilities;
import org.simplejavamail.api.mailer.SmtpConnectionReport;
import org.simplejavamail.api.mailer.SmtpTlsDetails;
import org.simplejavamail.api.mailer.spi.SmtpConnectionProbeAdapter;
import org.simplejavamail.api.mailer.SmtpRecipientStatus;
import org.simplejavamail.api.mailer.SmtpServerResponse;
import org.simplejavamail.api.mailer.config.AsyncQueueOverflowPolicy;
import org.simplejavamail.api.mailer.config.Pkcs12Config;
import org.simplejavamail.api.mailer.spi.ContentRequirement;
import org.simplejavamail.api.mailer.spi.DeliveryEnvelope;
import org.simplejavamail.api.mailer.spi.MailTransportAdapter;
import org.simplejavamail.api.mailer.spi.MailTransportLifecycleAdapter;
import org.simplejavamail.api.mailer.spi.MailTransportResult;
import org.simplejavamail.api.mailer.spi.PreparedMail;
import org.simplejavamail.api.outlook.OutlookEmailConversionResult;
import org.simplejavamail.config.ConfigDiagnosticGroup;
import org.simplejavamail.config.ConfigLoader;
import org.simplejavamail.config.ConfigPropertyDiagnostic;
import org.simplejavamail.converter.EmailConverter;

import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Properties;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.BiConsumer;
import java.util.function.BiFunction;
import java.util.function.Consumer;
import java.util.function.Function;

/**
 * This little program catches accidental Angus dependencies by exercising Simple Java Mail without Angus and checking that real MIME conversion fails
 * with a helpful missing-provider message instead of a linkage error.
 * <p>
 * Forked classpath compatibility probe for the packaged {@code simple-java-mail} JAR when no Jakarta Mail implementation or registered
 * {@link MailTransportAdapter} is present.
 * <p>
 * During Maven {@code verify}, the {@code verify-provider-neutral-consumers} profile compiles this source separately and launches it in a fresh JVM on the
 * ordinary classpath. That classpath contains the packaged Simple Java Mail JAR and its provider-neutral dependencies, but deliberately excludes
 * {@code angus-mail-provider-module} and Angus Mail. The probe verifies that:
 * <ul>
 *     <li>ordinary email building, Java 11 conveniences, mail-send observation, configuration diagnostics, and exact-EML API signatures remain usable
 *     without Angus;</li>
 *     <li>a third-party {@link Transport} and {@link MailTransportAdapter} can be compiled against the public SPI without Angus; and</li>
 *     <li>an operation that really needs a Jakarta Mail implementation fails with an actionable diagnostic.</li>
 * </ul>
 * No message is submitted. {@link FakeTransport} and {@link FakeMailTransportAdapter} are unregistered compile-time probes, not a functioning provider.
 * <p>
 * Keep the assertions aligned with {@code ProviderNeutralConsumer}, which runs the equivalent probe as a named JPMS module.
 */
public final class ProviderNeutralClasspathConsumer {

	/** Runs all classpath linkage and failure-diagnostic assertions; success is intentionally silent. */
	public static void main(final String[] args) throws Exception {
		final Properties configProperties = new Properties();
		configProperties.setProperty(ConfigLoader.Property.DEFAULT_SUBJECT.key(), "provider neutral");
		final SimpleJavaMail simpleJavaMail = SimpleJavaMail.withConfig(ConfigLoader.builder().withProperties(configProperties).load());
		final Email source = simpleJavaMail.emailBuilder().startingBlank()
				.from("sender@example.com")
				.withRecipients(new Recipient(null, "receiver@example.com", Message.RecipientType.TO, null))
				.withPlainText("conversion without a mail provider")
				.withHTMLText("<p>provider-neutral HTML</p>")
				.withAttachment("proof.txt", "provider-neutral attachment".getBytes(StandardCharsets.UTF_8), "text/plain")
				.buildEmailCompletedWithDefaultsAndOverrides();
		assertMailSendObserverApiIsAvailable(simpleJavaMail);
		assertAsyncQueueApiIsAvailable(simpleJavaMail);
		assertExecutionControlApiIsAvailable(simpleJavaMail, source);
		assertUnknownTransportFailureApiIsAvailable();
		assertRecipientReplyApiIsAvailable();
		assertConnectionProbeApiIsAvailable();
		assertExecutionViewsApiIsAvailable();
		assertEnvelopeIdentifierApiIsAvailable(simpleJavaMail);
		assertSigningTemplateApiIsAvailable(simpleJavaMail);
		assertRecipientDsnApiIsAvailable(simpleJavaMail);
		assertConfigDiagnosticsApiIsAvailable(simpleJavaMail);
		assertExactEmailApiIsAvailable(simpleJavaMail);
		assertJava11ConvenienceApiIsAvailable(simpleJavaMail, source);
		assertAngusIsAbsent();
		assertMissingImplementationFailsClearly(source);
	}

	/** Recipient policies and their ordered SPI representation must not load Angus. */
	private static void assertRecipientDsnApiIsAvailable(final SimpleJavaMail simpleJavaMail) {
		final Recipient recipient = new RecipientBuilder().withAddress("recipient@example.org").withType(Message.RecipientType.TO)
				.withDeliveryStatusNotificationNotifyOptions(DeliveryStatusNotification.NotifyOption.NEVER).build();
		final Email email = simpleJavaMail.emailBuilder().startingBlank()
				.withRecipients(new RecipientsBuilder().withDefaultDeliveryStatusNotificationNotifyOptions(DeliveryStatusNotification.NotifyOption.FAILURE)
						.withRecipient(recipient).buildRecipients()).buildEmail();
		final DeliveryEnvelope envelope = new DeliveryEnvelope(null, null,
				List.of(new DeliveryRecipient(recipient.getAddress(), recipient.getDeliveryStatusNotificationNotifyOptions())));
		if (!email.getRecipients().get(0).equals(recipient) || !envelope.hasRecipientNotifyOptions()
				|| !envelope.getRecipientOptions().get(0).getNotifyOptions().contains(DeliveryStatusNotification.NotifyOption.NEVER)) {
			throw new AssertionError("Provider-neutral recipient NOTIFY preferences are unavailable");
		}
		@SuppressWarnings("unused") final BiFunction<ExactEmailBuilder, Recipient[], ExactEmailBuilder> exactRecipients =
				ExactEmailBuilder::withEnvelopeRecipients;
		@SuppressWarnings("unused") final BiFunction<ExactEmailBuilder, DeliveryStatusNotification.NotifyOption[], ExactEmailBuilder> exactNotificationOptions =
				ExactEmailBuilder::withDeliveryStatusNotificationNotifyOptions;
		@SuppressWarnings("unused") final BiFunction<ExactEmailBuilder, DeliveryStatusNotification.ReturnOption, ExactEmailBuilder> exactReturnOption =
				ExactEmailBuilder::withDeliveryStatusNotificationReturnOption;
	}

	/** Constructing and sharing signing policy must not load a signing module or SMTP provider. */
	private static void assertSigningTemplateApiIsAvailable(final SimpleJavaMail simpleJavaMail) {
		final DkimConfig signing = DkimConfig.builder().dkimPrivateKeyData("key").dkimSigningDomain("example.org").dkimSelector("selector").build();
		final Email template = simpleJavaMail.emailBuilder().startingBlank().signWithDomainKey(signing).buildEmail();
		final Email configuredTemplate = simpleJavaMail.mailerBuilder().withEmailDefaults(template).getEmailDefaults();
		if (configuredTemplate != template || template.getFromRecipient() != null || template.getDkimConfig() != signing) {
			throw new AssertionError("Provider-neutral signing templates are unavailable");
		}
	}

	/** ENVID remains ordinary immutable Email data even when no SMTP implementation is installed. */
	private static void assertEnvelopeIdentifierApiIsAvailable(final SimpleJavaMail simpleJavaMail) {
		final DeliveryStatusNotification notification = DeliveryStatusNotification.builder().envelopeId("consumer+42").build();
		final Email email = simpleJavaMail.emailBuilder().startingBlank().withDeliveryStatusNotification(notification)
				.withDeliveryStatusNotificationReturnOption(DeliveryStatusNotification.ReturnOption.HEADERS_ONLY).buildEmail();
		if (!"consumer+42".equals(email.getDeliveryStatusNotification().getEnvelopeId())
				|| !notification.equals(notification.toBuilder().build())) {
			throw new AssertionError("Provider-neutral ENVID model is unavailable");
		}
		@SuppressWarnings("unused") final BiFunction<ExactEmailBuilder, String, ExactEmailBuilder> exactIdentifier =
				ExactEmailBuilder::fixingEnvelopeId;
		@SuppressWarnings("unused") final Function<MailSubmissionReceipt, String> effectiveEnvelopeId = MailSubmissionReceipt::getEnvelopeId;
		@SuppressWarnings("unused") final BiFunction<MailTransportResult, String, MailTransportResult> reportedEnvelopeId = MailTransportResult::withEnvelopeId;
		@SuppressWarnings("unused") final BiFunction<MailTransportAdapter, DeliveryEnvelope, Boolean> envelopeSupport =
				MailTransportAdapter::supportsDeliveryEnvelope;
	}

	/** Both views and receipt-bearing sends must compile without pulling provider types into the public API. */
	private static void assertExecutionViewsApiIsAvailable() {
		@SuppressWarnings("unused") final Function<Mailer, Mailer.Sync> sync = Mailer::sync;
		@SuppressWarnings("unused") final Function<Mailer, Mailer.Async> async = Mailer::async;
		@SuppressWarnings("unused") final BiConsumer<Mailer.Sync, Email> ignoreReceipt = Mailer.Sync::sendMail;
		@SuppressWarnings("unused") final BiFunction<Mailer.Sync, Email, MailSubmissionReceipt> receipt = Mailer.Sync::sendMail;
		@SuppressWarnings("unused") final BiFunction<Mailer.Async, Email, MailSend<MailSubmissionReceipt>> send = Mailer.Async::sendMail;
		@SuppressWarnings("unused") final BiConsumer<Mailer.Sync, Iterable<Email>> batch = Mailer.Sync::sendMailsInSimpleBatch;
		@SuppressWarnings("unused") final BiFunction<Mailer.Async, Iterable<Email>, MailSend<Void>> asyncBatch = Mailer.Async::sendMailsInSimpleBatch;
		@SuppressWarnings("unused") final Consumer<Mailer.Sync> connectionTest = Mailer.Sync::testConnection;
		@SuppressWarnings("unused") final Function<Mailer.Async, CompletableFuture<Void>> asyncTest = Mailer.Async::testConnection;
	}

	/** The connection report and its optional adapter must link without Angus, including all four Mailer entry points. */
	private static void assertConnectionProbeApiIsAvailable() {
		@SuppressWarnings("unused") final Function<Mailer.Sync, SmtpConnectionReport> probe = Mailer.Sync::probeConnection;
		@SuppressWarnings("unused") final BiFunction<Mailer.Sync, Boolean, SmtpConnectionReport> authenticatedProbe = Mailer.Sync::probeConnection;
		@SuppressWarnings("unused") final Function<Mailer.Async, CompletableFuture<SmtpConnectionReport>> asyncProbe = Mailer.Async::probeConnection;
		@SuppressWarnings("unused") final BiFunction<Mailer.Async, Boolean, CompletableFuture<SmtpConnectionReport>> asyncAuthProbe = Mailer.Async::probeConnection;
		@SuppressWarnings("unused") final Function<SmtpConnectionProbeAdapter, Class<?>> adapterType = Object::getClass;
		final SmtpConnectionReport report = SmtpConnectionReport.builder().host("localhost").port(25).protocol("smtp")
				.startedAt(Instant.EPOCH).completedAt(Instant.EPOCH).supported(true).connected(true).tlsActive(true)
				.afterTls(new SmtpCapabilities(Map.of("DSN", List.of(""), "SIZE", List.of("100"))))
				.tlsDetails(new SmtpTlsDetails("TLSv1.3", "example", List.of("CN=localhost"), true, null)).warnings(List.of()).build();
		if (!report.isSuccessful() || !report.getEffectiveCapabilities().orElseThrow().supports("DSN")
				|| report.getEffectiveCapabilities().orElseThrow().getMaximumMessageSize().orElseThrow() != 100) {
			throw new AssertionError("SMTP connection probe API is unavailable");
		}
	}

	/** Verifies the factory snapshot and grouped diagnostic API can be linked through the public config package. */
	private static void assertConfigDiagnosticsApiIsAvailable(final SimpleJavaMail simpleJavaMail) {
		final ConfigPropertyDiagnostic subjectDiagnostic = simpleJavaMail.getConfig()
				.getDiagnostics()
				.getProperties(ConfigDiagnosticGroup.EMAIL_DEFAULTS)
				.get(0);
		if (!ConfigLoader.Property.DEFAULT_SUBJECT.key().equals(subjectDiagnostic.getPropertyName())
				|| subjectDiagnostic.isRedacted()) {
			throw new AssertionError("Configuration diagnostics API is unavailable");
		}
	}

	/** Queue configuration and diagnostics must not depend on an SMTP implementation. */
	private static void assertAsyncQueueApiIsAvailable(final SimpleJavaMail simpleJavaMail) {
		if (simpleJavaMail.mailerBuilder().withAsyncQueueCapacity(4)
				.withAsyncQueueOverflowPolicy(AsyncQueueOverflowPolicy.WAIT_FOR_CAPACITY)
				.withAsyncQueueWaitTimeoutMillis(250).getAsyncQueueConfig().getCapacity() != 4) {
			throw new AssertionError("Async queue configuration API is unavailable");
		}
		// Compile the optional return type without constructing a Mailer, which would need a Jakarta Mail implementation here.
		@SuppressWarnings("unused") final Function<Mailer, Optional<AsyncQueueSnapshot>> inspection = Mailer::getAsyncQueueSnapshot;
		if (new MailSendRejectedException(AsyncQueueRejectionReason.QUEUE_FULL).getReason() != AsyncQueueRejectionReason.QUEUE_FULL) {
			throw new AssertionError("Async queue diagnostics API is unavailable");
		}
	}

	/** Completion views and optional control must link without a provider implementation. */
	private static void assertExecutionControlApiIsAvailable(final SimpleJavaMail mail, final Email email) {
		final CompletableFuture<Void> producer = new CompletableFuture<>();
		final AtomicInteger requests = new AtomicInteger();
		final MailSend<Void> send = new MailSend<>(producer, requests::incrementAndGet);
		send.getCompletion().cancel(true);
		send.requestCancellation();
		send.requestCancellation();
		producer.complete(null);
		if (requests.get() != 1 || !send.getCompletion().isDone() || send.getCompletion().isCancelled()) {
			throw new AssertionError("Completion views affected the operation");
		}
		if (mail.mailerBuilder().withMailSendTimeout(Duration.ofSeconds(1))
				.withMailSendObserver(outcome -> { }, Runnable::run).getMailSendTimeout() == null
				|| new MailSendCancelledException(null, null).getSubmissionReceipt().isPresent()
				|| new MailSendTimeoutException(null, null).getSubmissionReceipt().isPresent()) {
			throw new AssertionError("Execution control API is unavailable");
		}
		@SuppressWarnings("unused") final Function<Mailer, MailSend<MailSubmissionReceipt>> start = mailer -> mailer.async().sendMail(email);
		@SuppressWarnings("unused") final BiFunction<MailTransportLifecycleAdapter, Transport, Optional<Runnable>> abort = MailTransportLifecycleAdapter::createAbortAction;
	}

	private static void assertAngusIsAbsent() {
		try {
			Class.forName("org.eclipse.angus.mail.smtp.SMTPTransport");
			throw new AssertionError("Angus unexpectedly appeared on the provider-neutral classpath");
		} catch (ClassNotFoundException expected) {
			// expected: this runtime path intentionally contains no Jakarta Mail implementation
		}
	}

	/**
	 * Verifies the exact-EML entry point and preservation enum are present without invoking parsing, which intentionally needs a mail implementation.
	 */
	private static void assertExactEmailApiIsAvailable(final SimpleJavaMail simpleJavaMail) {
		final Function<byte[], ExactEmailBuilder> exactStarter =
				simpleJavaMail.emailBuilder()::startingFromExactEml;
		if (exactStarter == null || ContentRequirement.valueOf("PRESERVE_ALL_BYTES") != ContentRequirement.PRESERVE_ALL_BYTES) {
			throw new AssertionError("Exact EML API is unavailable");
		}
	}

	/** Verifies that the Java 11 Path and Instant conveniences link without opening files or invoking a mail provider. */
	private static void assertJava11ConvenienceApiIsAvailable(final SimpleJavaMail simpleJavaMail, final Email source) {
		final Function<Path, ConfigLoader> pathConfigSource = ConfigLoader.builder()::withPropertiesFile;
		final Function<Path, ExactEmailBuilder> exactPathStarter = simpleJavaMail.emailBuilder()::startingFromExactEml;
		final EmailPopulatingBuilder builder = simpleJavaMail.emailBuilder().startingBlank();
		final Function<Path, EmailPopulatingBuilder> bodyPathReader = builder::withPlainText;
		final Function<Instant, EmailPopulatingBuilder> sentDateFixer = builder::fixingSentDate;
		final Function<Path, DkimConfig.DkimConfigBuilder> dkimPathReader = DkimConfig.builder()::dkimPrivateKeyPath;
		final Function<Path, Pkcs12Config.Pkcs12ConfigBuilder> pkcs12PathReader = Pkcs12Config.builder()::pkcs12Store;
		final Function<Path, SmimeEncryptionConfig.SmimeEncryptionConfigBuilder> certificatePathReader =
				SmimeEncryptionConfig.builder()::x509Certificate;
		final Function<Path, SmimeSigningConfig.SmimeSigningConfigBuilder> signingStorePathReader = path ->
				SmimeSigningConfig.builder().pkcs12Config(path, "password", "alias", "password");
		final Function<Path, Email> emlPathReader = EmailConverter::emlToEmail;
		final Function<Path, OutlookEmailConversionResult> outlookPathReader = EmailConverter::outlookMsgToEmailBuilderWithOutlookData;

		if (pathConfigSource == null || exactPathStarter == null || bodyPathReader == null || sentDateFixer == null
				|| dkimPathReader == null || pkcs12PathReader == null || certificatePathReader == null || emlPathReader == null
				|| signingStorePathReader == null || outlookPathReader == null || source.getSentDateAsInstant() != null) {
			throw new AssertionError("Java 11 convenience API is unavailable");
		}
	}

	/** Verifies the complete observer API can be linked and configured without constructing a transport or sending an email. */
	private static void assertMailSendObserverApiIsAvailable(final SimpleJavaMail simpleJavaMail) {
		final MailSendObserver observer = outcome -> {
			outcome.getInitialMessageId();
			outcome.getEffectiveMessageId();
			outcome.getRequestedAt();
			outcome.getReadyAt();
			outcome.getStartedAt();
			outcome.getCompletedAt();
			outcome.isSuccessful();
			outcome.isLoggingOnly();
			outcome.getSubmissionReceipt();
			outcome.getFailure();
		};
		simpleJavaMail.mailerBuilder()
				.withSMTPServer("localhost", 25)
				.withMailSendObserver(observer);
	}

	/** Verifies that third-party adapters can report a failed submission whose final acceptance remains unknown. */
	private static void assertUnknownTransportFailureApiIsAvailable() {
		final MessagingException failure = new MessagingException("final response unavailable");
		final MailTransportResult result = MailTransportResult.failedWithUnknownAcceptance(failure, null, null);
		if (result.getStatus() != MailSubmissionStatus.UNKNOWN || result.getFailure().orElse(null) != failure) {
			throw new AssertionError("Unknown transport-failure API is unavailable");
		}
	}

	/** Exercises structured recipient results and retry guidance without linking an SMTP implementation. */
	private static void assertRecipientReplyApiIsAvailable() throws Exception {
		final MailRecipientResult recipient = new MailRecipientResult("recipient@example.org", "recipient@example.org",
				MailRecipientDisposition.VALID_UNSENT, true, new SmtpServerResponse(450, "450 4.2.0 busy"));
		final MailTransportResult result = MailTransportResult.failed(new MessagingException("rejected"), null,
				null, new Address[]{new InternetAddress("recipient@example.org")}, null)
				.withRecipientResults(List.of(recipient), MailRetryDisposition.SAFE_TO_RETRY_ALL);
		final MailSubmissionReceipt receipt = new MailSubmissionReceipt(null, null, Instant.now(), MailSubmissionStatus.REJECTED,
				result.getRecipientResults(), result.getRetryDisposition());
		if (receipt.getRecipientResults().get(0).getRcptStatus() != SmtpRecipientStatus.TEMPORARILY_REJECTED
				|| !recipient.getRcptAttempted().orElse(false)
				|| !"4.2.0".equals(recipient.getRcptResponse().orElseThrow().getEnhancedStatusCode().orElse(null))
				|| receipt.getRetryableRecipients().size() != 1 || receipt.getValidUnsentRecipients().size() != 1) {
			throw new AssertionError("Recipient reply API is unavailable");
		}
	}

	private static void assertMissingImplementationFailsClearly(final Email email) {
		try {
			EmailConverter.emailToMimeMessage(email);
			throw new AssertionError("Conversion unexpectedly worked without a Jakarta Mail implementation");
		} catch (IllegalStateException expected) {
			if (!expected.getMessage().contains("needs a Jakarta Mail implementation")) {
				throw new AssertionError("Missing implementation error was not actionable", expected);
			}
		}
	}

	/**
	 * Minimal third-party adapter implementation used only to prove the SPI has no Angus linkage. It is deliberately absent from service registration.
	 */
	public static final class FakeMailTransportAdapter implements MailTransportAdapter {
		@Override
		public boolean supports(final Transport transport) {
			return transport instanceof FakeTransport;
		}

		@Override
		public MailTransportResult sendMessage(final Transport transport, final PreparedMail preparedMail) {
			return MailTransportResult.unknown(null);
		}
	}

	/** Minimal provider-owned transport type recognized by {@link FakeMailTransportAdapter}; it is never registered, connected, or used by this probe. */
	public static final class FakeTransport extends Transport {
		private static Message sentMessage;

		public FakeTransport(final Session session, final URLName urlName) {
			super(session, urlName);
		}

		@Override
		protected boolean protocolConnect(final String host, final int port, final String user, final String password) {
			return true;
		}

		@Override
		public void sendMessage(final Message message, final Address[] addresses) {
			sentMessage = message;
		}
	}
}
