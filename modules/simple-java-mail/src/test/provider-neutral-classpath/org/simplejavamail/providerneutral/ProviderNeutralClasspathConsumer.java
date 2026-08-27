package org.simplejavamail.providerneutral;

import jakarta.mail.Address;
import jakarta.mail.Message;
import jakarta.mail.Session;
import jakarta.mail.Transport;
import jakarta.mail.URLName;
import org.simplejavamail.api.email.Email;
import org.simplejavamail.api.email.Recipient;
import org.simplejavamail.api.mailer.MailSendObserver;
import org.simplejavamail.api.mailer.spi.MailTransportAdapter;
import org.simplejavamail.api.mailer.spi.MailTransportResult;
import org.simplejavamail.api.mailer.spi.PreparedMail;
import org.simplejavamail.converter.EmailConverter;
import org.simplejavamail.api.SimpleJavaMail;
import org.simplejavamail.config.ConfigLoader;

import java.util.Properties;

public final class ProviderNeutralClasspathConsumer {
    public static void main(final String[] args) throws Exception {
		final Properties configProperties = new Properties();
		configProperties.setProperty(ConfigLoader.Property.DEFAULT_SUBJECT.key(), "provider neutral");
		final SimpleJavaMail simpleJavaMail = SimpleJavaMail.withConfig(ConfigLoader.builder().withProperties(configProperties).load());
        final Email source = simpleJavaMail.emailBuilder().startingBlank()
                .from("sender@example.com")
                .withRecipients(new Recipient(null, "receiver@example.com", jakarta.mail.Message.RecipientType.TO, null))
                .withPlainText("conversion without a mail provider")
				.withHTMLText("<p>provider-neutral HTML</p>")
				.withAttachment("proof.txt", "provider-neutral attachment".getBytes(java.nio.charset.StandardCharsets.UTF_8), "text/plain")
				.buildEmailCompletedWithDefaultsAndOverrides();
		assertMailSendObserverApiIsAvailable(simpleJavaMail);
		assertAngusIsAbsent();
		assertMissingImplementationFailsClearly(source);
    }

	private static void assertAngusIsAbsent() {
		try {
			Class.forName("org.eclipse.angus.mail.smtp.SMTPTransport");
			throw new AssertionError("Angus unexpectedly appeared on the provider-neutral classpath");
		} catch (ClassNotFoundException expected) {
			// expected: this consumer supplies its own Jakarta Mail transport
		}
	}

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
