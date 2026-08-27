package org.simplejavamail.providerneutral;

import jakarta.mail.Address;
import jakarta.mail.Message;
import jakarta.mail.Session;
import jakarta.mail.Transport;
import jakarta.mail.URLName;
import org.simplejavamail.api.SimpleJavaMail;
import org.simplejavamail.api.email.Email;
import org.simplejavamail.api.email.ExactEmailBuilder;
import org.simplejavamail.api.email.Recipient;
import org.simplejavamail.api.mailer.MailSendObserver;
import org.simplejavamail.api.mailer.spi.ContentRequirement;
import org.simplejavamail.api.mailer.spi.MailTransportAdapter;
import org.simplejavamail.api.mailer.spi.MailTransportResult;
import org.simplejavamail.api.mailer.spi.PreparedMail;
import org.simplejavamail.config.ConfigLoader;
import org.simplejavamail.converter.EmailConverter;

import java.nio.charset.StandardCharsets;
import java.util.Properties;
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
 *     <li>ordinary email building, mail-send observation, and exact-EML API signatures remain usable without linking to Angus types;</li>
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
		assertExactEmailApiIsAvailable(simpleJavaMail);
		assertAngusIsAbsent();
		assertMissingImplementationFailsClearly(source);
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
