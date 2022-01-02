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
	private final Wiser wiser;

	public SmtpServerRule(@NotNull Integer port) {
		this.wiser = Wiser.accepter((from, recipient) -> true).port(port);
	}

	@Override
	protected void before() {
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
		WiserMessage wiserMessage = messages.remove(0);
		assertThat(wiserMessage.getEnvelopeReceiver()).isEqualTo(envelopeReceiver);
		return wiserMessage.getMimeMessage();
	}
	
	@NotNull
	public MimeMessageAndEnvelope getOnlyMessage()
			throws MessagingException {
		checkState("getMessages()");
		List<WiserMessage> messages = getMessages();
		assertThat(messages).hasSize(1);
		WiserMessage wiserMessage = messages.remove(0);
		return new MimeMessageAndEnvelope(wiserMessage.getMimeMessage(), wiserMessage.getEnvelopeSender());
	}
	
	@NotNull
	public MimeMessage getMessage(String envelopeReceiver)
			throws MessagingException {
		checkState("getMessages()");
		List<WiserMessage> messages = getMessages();

		WiserMessage wiserMessage = messages.stream()
				.filter(m->m.getEnvelopeReceiver().equals(envelopeReceiver))
				.findFirst()
				.orElseThrow(() -> new AssertionError("message not found for recipient " + envelopeReceiver));

		messages.remove(wiserMessage);
		return wiserMessage.getMimeMessage();
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