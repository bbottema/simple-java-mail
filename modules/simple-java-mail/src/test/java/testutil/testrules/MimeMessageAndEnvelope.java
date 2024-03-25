package testutil.testrules;

import jakarta.mail.internet.MimeMessage;

public class MimeMessageAndEnvelope {
	private final MimeMessage mimeMessage;
	private final String envelopeSender;
	private final String envelopeReceiver;

	public MimeMessageAndEnvelope(final MimeMessage mimeMessage, final String envelopeSender, String envelopeReceiver) {
		this.mimeMessage = mimeMessage;
		this.envelopeSender = envelopeSender;
		this.envelopeReceiver = envelopeReceiver;
	}

	public MimeMessage getMimeMessage() {
		return mimeMessage;
	}

	public String getEnvelopeSender() {
		return envelopeSender;
	}

	public String getEnvelopeReceiver() {
		return envelopeReceiver;
	}
}