package testutil.testrules;

import jakarta.mail.MessagingException;
import jakarta.mail.internet.MimeMessage;
import org.jetbrains.annotations.NotNull;
import org.junit.rules.ExternalResource;
import org.subethamail.smtp.server.SMTPServer;
import org.subethamail.wiser.Wiser;
import org.subethamail.wiser.WiserMessage;

import java.io.IOException;
import java.io.InputStream;
import java.io.PrintStream;
import java.util.Iterator;
import java.util.List;

import static java.lang.String.format;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * SmtpServerRule - a TestRule wrapping a Wiser instance (a SMTP server in Java), started and stopped right before and after each test.
 * <br>
 * SmtpServerRule exposes the same methods as the {@link Wiser} instance by delegating the implementation to the instance. These methods, however, can not be
 * used outside a JUnit statement (otherwise an {@link IllegalStateException} is raised).
 * <br>
 * The {@link Wiser} instance can be directly retrieved but also only from inside a JUnit statement.
 */
public class SmtpServerRule extends ExternalResource {
	private final Wiser wiser = new Wiser();
	private final int port;

	public SmtpServerRule(@NotNull Integer port) {
		this.port = port;
	}

	@Override
	protected void before() {
		this.wiser.setPort(port);
		this.wiser.start();
	}

	@Override
	protected void after() {
		this.wiser.stop();
	}

	@NotNull
	public Wiser getWiser() {
		checkState("getWiser()");
		return this.wiser;
	}

	@NotNull
	public List<WiserMessage> getMessages() {
		checkState("getMessages()");
		return wiser.getMessages();
	}
	
	@NotNull
	public MimeMessage getOnlyMessage(String envelopeReceiver)
			throws MessagingException {
		checkState("getMessages()");
		List<WiserMessage> messages = getMessages();
		assertThat(messages).hasSize(1);
		Iterator<WiserMessage> iterator = messages.iterator();
		WiserMessage wiserMessage = iterator.next();
		assertThat(wiserMessage.getEnvelopeReceiver()).isEqualTo(envelopeReceiver);
		MimeMessage mimeMessage = wiserMessage.getMimeMessage();
		iterator.remove();
		return mimeMessage;
	}
	
	@NotNull
	public MimeMessageAndEnvelope getOnlyMessage()
			throws MessagingException {
		checkState("getMessages()");
		List<WiserMessage> messages = getMessages();
		assertThat(messages).hasSize(1);
		Iterator<WiserMessage> iterator = messages.iterator();
		WiserMessage wiserMessage = iterator.next();
		iterator.remove();
		return new MimeMessageAndEnvelope(wiserMessage.getMimeMessage(), wiserMessage.getEnvelopeSender());
	}
	
	@NotNull
	public MimeMessage getMessage(String envelopeReceiver)
			throws MessagingException {
		checkState("getMessages()");
		List<WiserMessage> messages = getMessages();
		Iterator<WiserMessage> iterator = messages.iterator();
		while (iterator.hasNext()) {
			WiserMessage wiserMessage = iterator.next();
			if (wiserMessage.getEnvelopeReceiver().equals(envelopeReceiver)) {
				MimeMessage mimeMessage = wiserMessage.getMimeMessage();
				iterator.remove();
				return mimeMessage;
			}
		}
		throw new AssertionError("message not found for recipient " + envelopeReceiver);
	}

	@NotNull
	public SMTPServer getServer() {
		checkState("getServer()");
		return wiser.getServer();
	}

	public boolean accept(String from, String recipient) {
		checkState("accept(String, String)");
		return wiser.accept(from, recipient);
	}

	public void deliver(String from, String recipient, InputStream data)
			throws IOException {
		checkState("deliver(String, String, InputStream)");
		wiser.deliver(from, recipient, data);
	}

	public void dumpMessages(PrintStream out)
			throws MessagingException {
		checkState("dumpMessages(PrintStream)");
		wiser.dumpMessages(out);
	}

	private void checkState(String method) {
		if (this.wiser == null) {
			throw new IllegalStateException(format("%s must not be called outside of a JUnit statement", method));
		}
	}
}