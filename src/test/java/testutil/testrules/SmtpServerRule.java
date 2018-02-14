package testutil.testrules;

import org.assertj.core.util.Preconditions;
import org.junit.rules.ExternalResource;
import org.junit.rules.TestRule;
import org.subethamail.smtp.TooMuchDataException;
import org.subethamail.smtp.server.SMTPServer;
import org.subethamail.wiser.Wiser;
import org.subethamail.wiser.WiserMessage;

import javax.annotation.Nonnull;
import javax.mail.MessagingException;
import javax.mail.internet.MimeMessage;
import java.io.IOException;
import java.io.InputStream;
import java.io.PrintStream;
import java.util.Iterator;
import java.util.List;

import static java.lang.String.format;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * SmtpServerRule - a TestRule wrapping a Wiser instance (a SMTP server in Java) started and stoped right before and after each test.
 * <br>
 * SmtpServerRule exposes the same methods as the {@link Wiser} instance by delegating the implementation to the instance. These methods, however, can not be
 * used outside a JUnit statement (otherwise a {@link IllegalStateException} is raised).
 * <br>
 * The {@link Wiser} instance can be directly retrieved but also only from inside a JUnit statement.
 */
public class SmtpServerRule extends ExternalResource implements TestRule {
	private final SmtpServerSupport SmtpServerSupport;
	private Wiser wiser;

	public SmtpServerRule(@Nonnull final SmtpServerSupport SmtpServerSupport) {
		this.SmtpServerSupport = Preconditions.checkNotNull(SmtpServerSupport);
	}

	@Override
	protected void before() {
		this.wiser = new Wiser();
		this.wiser.setPort(SmtpServerSupport.getPort());
		this.wiser.setHostname(SmtpServerSupport.getHostname());
		this.wiser.start();
	}

	@Override
	protected void after() {
		this.wiser.stop();
	}

	@Nonnull
	public Wiser getWiser() {
		checkState("getWiser()");
		return this.wiser;
	}

	@Nonnull
	public List<WiserMessage> getMessages() {
		checkState("getMessages()");
		return wiser.getMessages();
	}
	
	@Nonnull
	public MimeMessage getOnlyMessage(final String envelopeReceiver)
			throws MessagingException {
		checkState("getMessages()");
		final List<WiserMessage> messages = getMessages();
		assertThat(messages).hasSize(1);
		final Iterator<WiserMessage> iterator = messages.iterator();
		final WiserMessage wiserMessage = iterator.next();
		assertThat(wiserMessage.getEnvelopeReceiver()).isEqualTo(envelopeReceiver);
		final MimeMessage mimeMessage = wiserMessage.getMimeMessage();
		iterator.remove();
		return mimeMessage;
	}
	
	@Nonnull
	public MimeMessage getOnlyMessage()
			throws MessagingException {
		checkState("getMessages()");
		final List<WiserMessage> messages = getMessages();
		assertThat(messages).hasSize(1);
		final Iterator<WiserMessage> iterator = messages.iterator();
		final MimeMessage mimeMessage = iterator.next().getMimeMessage();
		iterator.remove();
		return mimeMessage;
	}
	
	@Nonnull
	public MimeMessage getMessage(final String envelopeReceiver)
			throws MessagingException {
		checkState("getMessages()");
		final List<WiserMessage> messages = getMessages();
		final Iterator<WiserMessage> iterator = messages.iterator();
		while (iterator.hasNext()) {
			final WiserMessage wiserMessage = iterator.next();
			if (wiserMessage.getEnvelopeReceiver().equals(envelopeReceiver)) {
				final MimeMessage mimeMessage = wiserMessage.getMimeMessage();
				iterator.remove();
				return mimeMessage;
			}
		}
		throw new AssertionError("message not found for recipient " + envelopeReceiver);
	}

	@Nonnull
	public SMTPServer getServer() {
		checkState("getServer()");
		return wiser.getServer();
	}

	public boolean accept(final String from, final String recipient) {
		checkState("accept(String, String)");
		return wiser.accept(from, recipient);
	}

	public void deliver(final String from, final String recipient, final InputStream data)
			throws IOException {
		checkState("deliver(String, String, InputStream)");
		wiser.deliver(from, recipient, data);
	}

	public void dumpMessages(final PrintStream out)
			throws MessagingException {
		checkState("dumpMessages(PrintStream)");
		wiser.dumpMessages(out);
	}

	private void checkState(final String method) {
		if (this.wiser == null) {
			throw new IllegalStateException(format("%s must not be called outside of a JUnit statement", method));
		}
	}
}