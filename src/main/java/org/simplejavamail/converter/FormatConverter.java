package org.simplejavamail.converter;

import org.simplejavamail.converter.internal.MimeMessageHelper;
import org.simplejavamail.converter.internal.MimeMessageParser;
import org.simplejavamail.email.Email;

import javax.activation.DataSource;
import javax.mail.Message;
import javax.mail.MessagingException;
import javax.mail.Session;
import javax.mail.internet.InternetAddress;
import javax.mail.internet.MimeMessage;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.io.UnsupportedEncodingException;
import java.util.Map;
import java.util.Properties;

import static java.lang.String.format;
import static java.nio.charset.StandardCharsets.UTF_8;
import static org.simplejavamail.converter.internal.MimeMessageHelper.produceMimeMessage;
import static org.simplejavamail.internal.util.MiscUtil.extractCID;

/**
 * Utility to help convert {@link org.simplejavamail.email.Email} instances to other formats (MimeMessage, EML etc.) and vice versa.
 */
@SuppressWarnings("WeakerAccess")
public final class FormatConverter {

	private FormatConverter() {
		// util / helper class
	}

	/**
	 * @param mimeMessage The MimeMessage from which to create the email.
	 */
	public static Email mimeMessageToEmail(final MimeMessage mimeMessage) {
		final Email email = new Email(false);
		try {
			fillEmailFromMimeMessage(email, new MimeMessageParser(mimeMessage).parse());
		} catch (MessagingException | IOException e) {
			throw new FormatConverterException(format(FormatConverterException.PARSE_ERROR_MIMEMESSAGE, e.getMessage()), e);
		}
		return email;
	}

	private static void fillEmailFromMimeMessage(final Email email, final MimeMessageParser parser)
			throws MessagingException {
		final InternetAddress from = parser.getFrom();
		email.setFromAddress(from.getPersonal(), from.getAddress());
		final InternetAddress replyTo = parser.getReplyTo();
		email.setReplyToAddress(replyTo.getPersonal(), replyTo.getAddress());
		for (final Map.Entry<String, Object> header : parser.getHeaders().entrySet()) {
			email.addHeader(header.getKey(), header.getValue());
		}
		for (final InternetAddress to : parser.getTo()) {
			email.addRecipient(to.getPersonal(), to.getAddress(), Message.RecipientType.TO);
		}
		//noinspection QuestionableName
		for (final InternetAddress cc : parser.getCc()) {
			email.addRecipient(cc.getPersonal(), cc.getAddress(), Message.RecipientType.CC);
		}
		for (final InternetAddress bcc : parser.getBcc()) {
			email.addRecipient(bcc.getPersonal(), bcc.getAddress(), Message.RecipientType.BCC);
		}
		email.setSubject(parser.getSubject());
		email.setText(parser.getPlainContent());
		email.setTextHTML(parser.getHtmlContent());
		for (final Map.Entry<String, DataSource> cid : parser.getCidMap().entrySet()) {
			email.addEmbeddedImage(extractCID(cid.getKey()), cid.getValue());
		}
		for (final Map.Entry<String, DataSource> attachment : parser.getAttachmentList().entrySet()) {
			email.addAttachment(extractCID(attachment.getKey()), attachment.getValue());
		}
	}

	/**
	 * Delegates to {@link #emailToMimeMessage(Email, Session)}, using a new empty {@link Session} instance.
	 *
	 * @see #emailToMimeMessage(Email, Session)
	 */
	public static MimeMessage emailToMimeMessage(final Email email) {
		return emailToMimeMessage(email, Session.getDefaultInstance(new Properties()));
	}

	/**
	 * Refer to {@link MimeMessageHelper#produceMimeMessage(Email, Session)}
	 */
	public static MimeMessage emailToMimeMessage(final Email email, final Session session) {
		try {
			return produceMimeMessage(email, session);
		} catch (UnsupportedEncodingException | MessagingException e) {
			// this should never happen, so we don't acknowledge this exception (and simply bubble up)
			throw new RuntimeException(e.getMessage(), e);
		}
	}

	/**
	 * @return The result of {@link MimeMessage#writeTo(OutputStream)} which should be in the standard EML format.
	 */
	public static String mimeMessageToEMLString(final MimeMessage message) {
		final ByteArrayOutputStream os = new ByteArrayOutputStream();
		try {
			message.writeTo(os);
			return os.toString(UTF_8.name());
		} catch (IOException | MessagingException e) {
			// this should never happen, so we don't acknowledge this exception (and simply bubble up)
			throw new RuntimeException(e.getMessage(), e);
		}
	}

	/**
	 * Delegates to {@link #emailToMimeMessage(Email)} and passes the result to {@link #mimeMessageToEMLString(MimeMessage)}.
	 *
	 * @see #emailToMimeMessage(Email, Session)
	 */
	public static String emailToEMLString(final Email email) {
		return mimeMessageToEMLString(emailToMimeMessage(email));
	}
}