package org.simplejavamail.providerneutral;

import jakarta.mail.Address;
import jakarta.mail.Message;
import jakarta.mail.Provider;
import jakarta.mail.Session;
import jakarta.mail.Transport;
import jakarta.mail.URLName;
import jakarta.mail.internet.MimeMessage;
import org.simplejavamail.api.email.Email;
import org.simplejavamail.api.email.Recipient;
import org.simplejavamail.converter.EmailConverter;
import org.simplejavamail.email.EmailBuilder;
import org.simplejavamail.mailer.MailerBuilder;

import java.util.Properties;

public final class ProviderNeutralConsumer {
    public static void main(final String[] args) throws Exception {
        final Email source = EmailBuilder.startingBlank()
                .from("sender@example.com")
                .withRecipients(new Recipient(null, "receiver@example.com", jakarta.mail.Message.RecipientType.TO, null))
                .withSubject("provider neutral")
                .withPlainText("conversion without a mail provider")
				.withHTMLText("<p>provider-neutral HTML</p>")
				.withAttachment("proof.txt", "provider-neutral attachment".getBytes(java.nio.charset.StandardCharsets.UTF_8), "text/plain")
                .buildEmail();
        final MimeMessage mimeMessage = EmailConverter.emailToMimeMessage(source);
        final Email converted = EmailConverter.mimeMessageToEmail(mimeMessage);
        if (!"conversion without a mail provider".equals(converted.getPlainText())) {
            throw new AssertionError("Provider-neutral JPMS conversion failed");
        }
		if (!"<p>provider-neutral HTML</p>".equals(converted.getHTMLText())
				|| converted.getAttachments().size() != 1
				|| !"provider-neutral attachment".equals(converted.getAttachments().get(0).readAllData())) {
			throw new AssertionError("Provider-neutral JPMS multipart conversion failed");
		}

		assertAngusIsAbsent();
		assertGenericTransportSend(source);
    }

	private static void assertAngusIsAbsent() {
		try {
			Class.forName("org.eclipse.angus.mail.smtp.SMTPTransport");
			throw new AssertionError("Angus unexpectedly appeared on the provider-neutral module path");
		} catch (ClassNotFoundException expected) {
			// expected: this consumer supplies its own Jakarta Mail transport
		}
	}

	private static void assertGenericTransportSend(final Email email) throws Exception {
		final Properties properties = new Properties();
		properties.setProperty("mail.transport.protocol", "fake");
		final Session session = Session.getInstance(properties);
		final Provider provider = new Provider(Provider.Type.TRANSPORT, "fake", FakeTransport.class.getName(), "Simple Java Mail", "1");
		session.addProvider(provider);
		session.setProvider(provider);
		FakeTransport.sentMessage = null;
		MailerBuilder.usingSession(session).buildMailer().sendMail(email);
		if (FakeTransport.sentMessage == null || !"provider neutral".equals(FakeTransport.sentMessage.getSubject())) {
			throw new AssertionError("Provider-neutral JPMS generic transport send failed");
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
