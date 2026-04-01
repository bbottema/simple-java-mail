package testutil.testrules;

import jakarta.mail.MessagingException;
import jakarta.mail.internet.MimeMessage;
import lombok.RequiredArgsConstructor;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.junit.jupiter.api.extension.AfterEachCallback;
import org.junit.jupiter.api.extension.BeforeEachCallback;
import org.junit.jupiter.api.extension.ExtensionContext;
import org.subethamail.smtp.MessageContext;
import org.subethamail.smtp.auth.EasyAuthenticationHandlerFactory;
import org.subethamail.smtp.auth.LoginFailedException;
import org.subethamail.smtp.auth.UsernamePasswordValidator;
import org.subethamail.smtp.server.SMTPServer;
import org.subethamail.wiser.Wiser;
import org.subethamail.wiser.WiserMessage;

import java.io.IOException;
import java.io.InputStream;
import java.io.PrintStream;
import java.util.List;
import java.util.Objects;

import static java.lang.String.format;
import static org.assertj.core.api.Assertions.assertThat;

public class SmtpServerExtension implements BeforeEachCallback, AfterEachCallback {
	@NotNull final Integer port;
	@Nullable final String username;
	@Nullable final String password;
	private Wiser wiser;

	@RequiredArgsConstructor
	static class RequiredUsernamePasswordValidator implements UsernamePasswordValidator {
		@Nullable final String username;
		@Nullable final String password;

		@Override
		public void login(String username, String password, MessageContext context) throws LoginFailedException {
			if (!Objects.equals(this.username, username) || !Objects.equals(this.password, password)) {
				throw new LoginFailedException();
			}
		}
	}

	public SmtpServerExtension(@NotNull Integer port, @Nullable String username, @Nullable String password) {
		this.port = port;
		this.username = username;
		this.password = password;
	}

	@Override
	public void beforeEach(ExtensionContext context) {
		this.wiser = Wiser.create(SMTPServer.port(port)
				.authenticationHandlerFactory(new EasyAuthenticationHandlerFactory(new RequiredUsernamePasswordValidator(username, password)))
				.requireAuth(password != null));

		this.wiser.start();
	}

	@Override
	public void afterEach(ExtensionContext context) {
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
	public MimeMessage getOnlyMessage(String envelopeReceiver) throws MessagingException {
		checkState("getOnlyMessage(String)");
		List<WiserMessage> messages = getMessages();
		assertThat(messages).hasSize(1);
		WiserMessage wiserMessage = messages.remove(0);
		assertThat(wiserMessage.getEnvelopeReceiver()).isEqualTo(envelopeReceiver);
		return wiserMessage.getMimeMessage();
	}

	@NotNull
	public MimeMessageAndEnvelope getOnlyMessage() throws MessagingException {
		checkState("getOnlyMessage()");
		List<WiserMessage> messages = getMessages();
		assertThat(messages).hasSize(1);
		WiserMessage wiserMessage = messages.remove(0);
		return new MimeMessageAndEnvelope(wiserMessage.getMimeMessage(), wiserMessage.getEnvelopeSender(), wiserMessage.getEnvelopeReceiver());
	}

	@NotNull
	public MimeMessage getMessage(String envelopeReceiver) throws MessagingException {
		checkState("getMessage(String)");
		List<WiserMessage> messages = getMessages();

		WiserMessage wiserMessage = messages.stream()
				.filter(m -> m.getEnvelopeReceiver().equals(envelopeReceiver))
				.findFirst()
				.orElseThrow(() -> new MessagingException("Message not found for recipient " + envelopeReceiver));

		messages.remove(wiserMessage);
		return wiserMessage.getMimeMessage();
	}

	public boolean accept(String from, String recipient) {
		checkState("accept(String, String)");
		return wiser.accept(from, recipient);
	}

	public void deliver(String from, String recipient, InputStream data) throws IOException {
		checkState("deliver(String, String, InputStream)");
		wiser.deliver(from, recipient, data);
	}

	public void dumpMessages(PrintStream out) throws MessagingException {
		checkState("dumpMessages(PrintStream)");
		wiser.dumpMessages(out);
	}

	private void checkState(String method) {
		if (this.wiser == null) {
			throw new IllegalStateException(format("%s must not be called outside of a JUnit statement", method));
		}
	}
}
