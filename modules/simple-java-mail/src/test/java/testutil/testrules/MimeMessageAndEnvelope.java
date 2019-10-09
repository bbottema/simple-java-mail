package testutil.testrules;

import javax.mail.internet.MimeMessage;

public class MimeMessageAndEnvelope {
	private final MimeMessage mimeMessage;
	private final String envelopeSender;

	public MimeMessageAndEnvelope(final MimeMessage mimeMessage, final String envelopeSender) {
		this.mimeMessage = mimeMessage;
		this.envelopeSender = envelopeSender;
	}

	public MimeMessage getMimeMessage() {
		return mimeMessage;
	}

	public String getEnvelopeSender() {
		return envelopeSender;
	}
}