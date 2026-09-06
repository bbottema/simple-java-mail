package org.simplejavamail.providerneutral;

import jakarta.mail.Address;
import jakarta.mail.Message;
import jakarta.mail.Session;
import jakarta.mail.Transport;
import jakarta.mail.URLName;
import org.simplejavamail.api.SimpleJavaMail;
import org.simplejavamail.api.email.Email;
import org.simplejavamail.api.email.EmailPopulatingBuilder;
import org.simplejavamail.api.email.ExactEmailBuilder;
import org.simplejavamail.api.email.Recipient;
import org.simplejavamail.api.email.config.DkimConfig;
import org.simplejavamail.api.email.config.SmimeEncryptionConfig;
import org.simplejavamail.api.email.config.SmimeSigningConfig;
import org.simplejavamail.api.mailer.MailSendObserver;
import org.simplejavamail.api.mailer.config.Pkcs12Config;
import org.simplejavamail.api.mailer.spi.ContentRequirement;
import org.simplejavamail.api.mailer.spi.MailTransportAdapter;
import org.simplejavamail.api.mailer.spi.MailTransportResult;
import org.simplejavamail.api.mailer.spi.PreparedMail;
import org.simplejavamail.api.outlook.OutlookEmailConversionResult;
import org.simplejavamail.config.ConfigDiagnosticGroup;
import org.simplejavamail.config.ConfigPropertyDiagnostic;
import org.simplejavamail.config.ConfigLoader;
import org.simplejavamail.converter.EmailConverter;

import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Instant;
import java.util.Properties;
import java.util.function.Function;

/**
 * This little program catches accidental Angus dependencies by exercising Simple Java Mail without Angus and checking that real MIME conversion fails
 * with a helpful missing-provider message instead of a linkage error.
 * <p>
 * Forked JPMS compatibility probe for the packaged {@code org.simplejavamail} module when no Jakarta Mail implementation or registered
 * {@link MailTransportAdapter} is present.
 * <p>
 * During Maven {@code verify}, the {@code verify-provider-neutral-consumers} profile compiles this source separately as
 * {@code org.simplejavamail.providerneutral.consumer} and launches it in a fresh JVM on the module path. That path contains the packaged Simple Java Mail
 * JAR and its provider-neutral dependencies, but deliberately excludes {@code org.simplejavamail.mailprovider.angus} and Angus Mail. The probe verifies
 * that:
 * <ul>
 *     <li>the exported email-building, Java 11 convenience, mail-send-observer, configuration-diagnostic, exact-EML, and transport-SPI types remain readable
 *     without Angus;</li>
 *     <li>a third-party {@link Transport} and {@link MailTransportAdapter} can be implemented from a named module without Angus; and</li>
 *     <li>an operation that really needs a Jakarta Mail implementation fails with an actionable diagnostic.</li>
 * </ul>
 * No message is submitted. {@link FakeTransport} and {@link FakeMailTransportAdapter} are unregistered compile-time probes, not a functioning provider.
 * <p>
 * Keep the assertions aligned with {@code ProviderNeutralClasspathConsumer}, which runs the equivalent probe on the ordinary classpath.
 */
public final class ProviderNeutralConsumer {

	/** Runs all module-path linkage and failure-diagnostic assertions; success is intentionally silent. */
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
		assertConfigDiagnosticsApiIsAvailable(simpleJavaMail);
		assertExactEmailApiIsAvailable(simpleJavaMail);
		assertJava11ConvenienceApiIsAvailable(simpleJavaMail, source);
		assertAngusIsAbsent();
		assertMissingImplementationFailsClearly(source);
	}

	/** Verifies the factory snapshot and grouped diagnostic API can be linked through the exported config package. */
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

	private static void assertAngusIsAbsent() {
		try {
			Class.forName("org.eclipse.angus.mail.smtp.SMTPTransport");
			throw new AssertionError("Angus unexpectedly appeared on the provider-neutral module path");
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
