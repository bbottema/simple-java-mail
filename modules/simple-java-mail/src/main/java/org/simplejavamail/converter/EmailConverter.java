package org.simplejavamail.converter;

import org.simplejavamail.api.email.AttachmentResource;
import org.simplejavamail.converter.internal.mimemessage.MimeMessageParser;
import org.simplejavamail.converter.internal.mimemessage.MimeMessageParser.ParsedMimeMessageComponents;
import org.simplejavamail.converter.internal.mimemessage.MimeMessageProducerHelper;
import org.simplejavamail.api.email.CalendarMethod;
import org.simplejavamail.api.email.Email;
import org.simplejavamail.email.EmailBuilder;
import org.simplejavamail.api.email.EmailPopulatingBuilder;
import org.simplejavamail.email.internal.EmailPopulatingBuilderImpl;
import org.simplejavamail.email.internal.EmailStartingBuilderImpl;
import org.simplejavamail.internal.modules.ModuleLoader;

import javax.activation.DataSource;
import javax.annotation.Nonnull;
import javax.mail.MessagingException;
import javax.mail.Session;
import javax.mail.internet.InternetAddress;
import javax.mail.internet.MimeMessage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.UnsupportedEncodingException;
import java.nio.file.FileSystems;
import java.nio.file.PathMatcher;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Properties;

import static java.lang.String.format;
import static java.nio.charset.StandardCharsets.UTF_8;
import static org.simplejavamail.internal.util.MiscUtil.extractCID;
import static org.simplejavamail.internal.util.MiscUtil.readInputStreamToString;
import static org.simplejavamail.internal.util.Preconditions.assumeNonNull;
import static org.simplejavamail.internal.util.Preconditions.checkNonEmptyArgument;

/**
 * Utility to help convert {@link org.simplejavamail.api.email.Email} instances to other formats (MimeMessage, EML etc.) and vice versa.
 *
 * If you use the Outlook parsing API, make sure you load the following dependency: <em>org.simplejavamail::outlook-message-parser</em>
 */
@SuppressWarnings("WeakerAccess")
public final class EmailConverter {
	
	private static final PathMatcher EML_PATH_MATCHER = FileSystems.getDefault().getPathMatcher("glob:**/*.eml");
	private static final PathMatcher MSG_PATH_MATCHER = FileSystems.getDefault().getPathMatcher("glob:**/*.msg");

	private EmailConverter() {
		// util / helper class
	}

	/*
		To Email instance
	 */
	
	/**
	 * @param mimeMessage The MimeMessage from which to create the {@link Email}.
	 */
	public static Email mimeMessageToEmail(@Nonnull final MimeMessage mimeMessage) {
		return mimeMessageToEmailBuilder(mimeMessage).buildEmail();
	}
	
	/**
	 * @param mimeMessage The MimeMessage from which to create the {@link Email}.
	 */
	public static EmailPopulatingBuilder mimeMessageToEmailBuilder(@Nonnull final MimeMessage mimeMessage) {
		checkNonEmptyArgument(mimeMessage, "mimeMessage");
		final EmailPopulatingBuilder emailPopulatingBuilder = EmailBuilder.ignoringDefaults().startingBlank();
		buildEmailFromMimeMessage(emailPopulatingBuilder, MimeMessageParser.parseMimeMessage(mimeMessage));
		return emailPopulatingBuilder;
	}

	/**
	 * @param msgData The content of an Outlook (.msg) message from which to create the {@link Email}.
	 */
	@SuppressWarnings("deprecation")
	public static Email outlookMsgToEmail(@Nonnull final String msgData) {
		return buildEmailAndDecryptAttachments(ModuleLoader.loadOutlookModule()
				.outlookMsgToEmailBuilder(msgData, new EmailStartingBuilderImpl()));
	}

	/**
	 * @param msgFile The content of an Outlook (.msg) message from which to create the {@link Email}.
	 */
	@SuppressWarnings("deprecation")
	public static Email outlookMsgToEmail(@Nonnull final File msgFile) {
		if (!MSG_PATH_MATCHER.matches(msgFile.toPath())) {
			throw new EmailConverterException(format(EmailConverterException.FILE_NOT_RECOGNIZED_AS_OUTLOOK, msgFile));
		}
		return buildEmailAndDecryptAttachments(ModuleLoader.loadOutlookModule()
				.outlookMsgToEmailBuilder(msgFile, new EmailStartingBuilderImpl()));
	}

	private static Email buildEmailAndDecryptAttachments(final EmailPopulatingBuilder emailBuilder) {
		return ((EmailPopulatingBuilderImpl) emailBuilder)
				.withDecryptedAttachments(decryptAttachments(emailBuilder.getAttachments()))
				.buildEmail();
	}

	@Nonnull
	private static List<AttachmentResource> decryptAttachments(final List<AttachmentResource> attachments) {
		return ModuleLoader.smimeModuleAvailable()
				? ModuleLoader.loadSMimeModule().decryptAttachments(attachments)
				: Collections.<AttachmentResource>emptyList();
	}

	/**
	 * @param msgInputStream The content of an Outlook (.msg) message from which to create the {@link Email}.
	 */
	public static Email outlookMsgToEmail(@Nonnull final InputStream msgInputStream) {
		return outlookMsgToEmailBuilder(msgInputStream).buildEmail();
	}
	
	/**
	 * @param msgInputStream The content of an Outlook (.msg) message from which to create the {@link Email}.
	 */
	@SuppressWarnings("deprecation")
	public static EmailPopulatingBuilder outlookMsgToEmailBuilder(@Nonnull final InputStream msgInputStream) {
		return ModuleLoader.loadOutlookModule().outlookMsgToEmailBuilder(msgInputStream, new EmailStartingBuilderImpl());
	}
	
	/**
	 * Delegates to {@link #emlToEmail(String)} with the full string value read from the given <code>InputStream</code>.
	 */
	public static Email emlToEmail(@Nonnull final InputStream emlInputStream) {
		try {
			return emlToEmail(readInputStreamToString(checkNonEmptyArgument(emlInputStream, "emlInputStream"), UTF_8));
		} catch (IOException e) {
			throw new EmailConverterException(EmailConverterException.ERROR_READING_EML_INPUTSTREAM, e);
		}
	}
	
	/**
	 * Delegates to {@link #emlToMimeMessage(String, Session)} using a dummy {@link Session} instance and passes the result to {@link
	 * #mimeMessageToEmail(MimeMessage)};
	 */
	public static Email emlToEmail(@Nonnull final String eml) {
		final MimeMessage mimeMessage = emlToMimeMessage(checkNonEmptyArgument(eml, "eml"), createDummySession());
		return mimeMessageToEmail(mimeMessage);
	}
	
	/**
	 * Delegates to {@link #emlToEmail(String)} with the full string value read from the given <code>InputStream</code>.
	 */
	public static EmailPopulatingBuilder emlToEmailBuilder(@Nonnull final InputStream emlInputStream) {
		try {
			return emlToEmailBuilder(readInputStreamToString(checkNonEmptyArgument(emlInputStream, "emlInputStream"), UTF_8));
		} catch (IOException e) {
			throw new EmailConverterException(EmailConverterException.ERROR_READING_EML_INPUTSTREAM, e);
		}
	}
	
	/**
	 * Delegates to {@link #emlToMimeMessage(String, Session)} using a dummy {@link Session} instance and passes the result to {@link
	 * #mimeMessageToEmail(MimeMessage)};
	 */
	public static EmailPopulatingBuilder emlToEmailBuilder(@Nonnull final String eml) {
		final MimeMessage mimeMessage = emlToMimeMessage(checkNonEmptyArgument(eml, "eml"), createDummySession());
		return mimeMessageToEmailBuilder(mimeMessage);
	}

	/*
		To MimeMessage instance
	 */

	/**
	 * @return Result of {@link #outlookMsgToEmail(String)} and {@link #emailToMimeMessage(Email)}
	 */
	@Nonnull
	public static MimeMessage outlookMsgToMimeMessage(@Nonnull final String outlookMsgData) {
		checkNonEmptyArgument(outlookMsgData, "outlookMsgData");
		return emailToMimeMessage(outlookMsgToEmail(outlookMsgData));
	}

	/**
	 * @return Result of {@link #outlookMsgToEmail(File)} and {@link #emailToMimeMessage(Email)}
	 */
	@Nonnull
	public static MimeMessage outlookMsgToMimeMessage(@Nonnull final File outlookMsgFile) {
		checkNonEmptyArgument(outlookMsgFile, "outlookMsgFile");
		return emailToMimeMessage(outlookMsgToEmail(outlookMsgFile));
	}

	/**
	 * @return Result of {@link #outlookMsgToEmail(InputStream)} and {@link #emailToMimeMessage(Email)}
	 */
	@Nonnull
	public static MimeMessage outlookMsgToMimeMessage(@Nonnull final InputStream outloookMsgInputStream) {
		checkNonEmptyArgument(outloookMsgInputStream, "outloookMsgInputStream");
		return emailToMimeMessage(outlookMsgToEmail(outloookMsgInputStream));
	}

	/**
	 * Delegates to {@link #emailToMimeMessage(Email, Session)}, using a new empty {@link Session} instance.
	 *
	 * @see #emailToMimeMessage(Email, Session)
	 */
	public static MimeMessage emailToMimeMessage(@Nonnull final Email email) {
		return emailToMimeMessage(checkNonEmptyArgument(email, "email"), createDummySession());
	}

	/**
	 * Refer to {@link MimeMessageProducerHelper#produceMimeMessage(Email, Session)}
	 */
	public static MimeMessage emailToMimeMessage(@Nonnull final Email email, @Nonnull final Session session) {
		try {
			return MimeMessageProducerHelper.produceMimeMessage(checkNonEmptyArgument(email, "email"), checkNonEmptyArgument(session, "session"));
		} catch (UnsupportedEncodingException | MessagingException e) {
			// this should never happen, so we don't acknowledge this exception (and simply bubble up)
			throw new AssertionError(e.getMessage(), e);
		}
	}
	
	/**
	 * Delegates to {@link #emlToMimeMessage(File, Session)}, using {@link #createDummySession()}.
	 *
	 * @see #emlToMimeMessage(File, Session)
	 */
	@Nonnull
	public static MimeMessage emlToMimeMessage(@Nonnull final File emlFile) {
		return emlToMimeMessage(emlFile, createDummySession());
	}
	
	/**
	 * Relies on JavaMail's native parser of EML data, {@link MimeMessage#MimeMessage(Session, InputStream)}.
	 *
	 * @see MimeMessage#MimeMessage(Session, InputStream)
	 */
	public static MimeMessage emlToMimeMessage(@Nonnull final File emlFile, @Nonnull final Session session) {
		if (!EML_PATH_MATCHER.matches(emlFile.toPath())) {
			throw new EmailConverterException(format(EmailConverterException.FILE_NOT_RECOGNIZED_AS_EML, emlFile));
		}
		try {
			InputStream source = new FileInputStream(checkNonEmptyArgument(emlFile, "emlFile"));
			return new MimeMessage(session, source);
		} catch (final MessagingException | FileNotFoundException e) {
			throw new EmailConverterException(format(EmailConverterException.PARSE_ERROR_EML, e.getMessage()), e);
		}
	}

	/**
	 * Delegates to {@link #emlToMimeMessage(String, Session)} with an empty {@link Session} instance.
	 *
	 * @see #emailToMimeMessage(Email, Session)
	 */
	public static MimeMessage emlToMimeMessage(@Nonnull final String eml) {
		return emlToMimeMessage(checkNonEmptyArgument(eml, "eml"), createDummySession());
	}

	/**
	 * Relies on JavaMail's native parser of EML data, {@link MimeMessage#MimeMessage(Session, InputStream)}.
	 */
	public static MimeMessage emlToMimeMessage(@Nonnull final String eml, @Nonnull final Session session) {
		checkNonEmptyArgument(session, "session");
		checkNonEmptyArgument(eml, "eml");
		try {
			return new MimeMessage(session, new ByteArrayInputStream(eml.getBytes(UTF_8)));
		} catch (final MessagingException e) {
			throw new EmailConverterException(format(EmailConverterException.PARSE_ERROR_EML, e.getMessage()), e);
		}
	}

	/*
		To EML String
	 */

	/**
	 * @return The result of {@link MimeMessage#writeTo(OutputStream)} which should be in the standard EML format.
	 */
	public static String mimeMessageToEML(@Nonnull final MimeMessage mimeMessage) {
		final ByteArrayOutputStream os = new ByteArrayOutputStream();
		try {
			checkNonEmptyArgument(mimeMessage, "mimeMessage").writeTo(os);
			return os.toString(UTF_8.name());
		} catch (IOException | MessagingException e) {
			// this should never happen, so we don't acknowledge this exception (and simply bubble up)
			throw new AssertionError("This should never happen", e);
		}
	}

	/**
	 * Delegates to {@link #emailToMimeMessage(Email)} and passes the result to {@link #mimeMessageToEML(MimeMessage)}.
	 *
	 * @see #emailToMimeMessage(Email, Session)
	 */
	public static String emailToEML(@Nonnull final Email email) {
		return mimeMessageToEML(emailToMimeMessage(checkNonEmptyArgument(email, "email")));
	}

	/**
	 * @return Result of {@link #outlookMsgToEmail(String)} and {@link #emailToEML(Email)}
	 */
	@Nonnull
	public static String outlookMsgToEML(@Nonnull final String outlookMsgData) {
		checkNonEmptyArgument(outlookMsgData, "outlookMsgData");
		return emailToEML(outlookMsgToEmail(outlookMsgData));
	}

	/**
	 * @return Result of {@link #outlookMsgToEmail(File)} and {@link #emailToEML(Email)}
	 */
	@Nonnull
	public static String outlookMsgToEML(@Nonnull final File outlookMsgFile) {
		checkNonEmptyArgument(outlookMsgFile, "outlookMsgFile");
		return emailToEML(outlookMsgToEmail(outlookMsgFile));
	}

	/**
	 * @return Result of {@link #outlookMsgToEmail(InputStream)} and {@link #emailToEML(Email)}
	 */
	@Nonnull
	public static String outlookMsgToEML(@Nonnull final InputStream outloookMsgInputStream) {
		checkNonEmptyArgument(outloookMsgInputStream, "outloookMsgInputStream");
		return emailToEML(outlookMsgToEmail(outloookMsgInputStream));
	}

	/*
		Helpers
	 */

	private static void buildEmailFromMimeMessage(@Nonnull final EmailPopulatingBuilder builder, @Nonnull final ParsedMimeMessageComponents parsed) {
		checkNonEmptyArgument(builder, "emailBuilder");
		checkNonEmptyArgument(parsed, "parsedMimeMessageComponents");
		if (parsed.getFromAddress() != null) {
			builder.from(parsed.getFromAddress().getPersonal(), parsed.getFromAddress().getAddress());
		}
		if (parsed.getReplyToAddresses() != null) {
			builder.withReplyTo(parsed.getReplyToAddresses().getPersonal(), parsed.getReplyToAddresses().getAddress());
		}
		builder.withHeaders(parsed.getHeaders());
		final InternetAddress dnTo = parsed.getDispositionNotificationTo();
		if (dnTo != null) {
			builder.withDispositionNotificationTo(dnTo);
		}
		final InternetAddress rrTo = parsed.getReturnReceiptTo();
		if (rrTo != null) {
			builder.withReturnReceiptTo(rrTo);
		}
		final InternetAddress bTo = parsed.getBounceToAddress();
		if (bTo != null) {
			builder.withBounceTo(bTo);
		}
		builder.fixingMessageId(parsed.getMessageId());
		for (final InternetAddress to : parsed.getToAddresses()) {
			builder.to(to);
		}
		//noinspection QuestionableName
		for (final InternetAddress cc : parsed.getCcAddresses()) {
			builder.cc(cc);
		}
		for (final InternetAddress bcc : parsed.getBccAddresses()) {
			builder.bcc(bcc);
		}
		builder.withSubject(parsed.getSubject() != null ? parsed.getSubject() : "");
		builder.withPlainText(parsed.getPlainContent());
		builder.withHTMLText(parsed.getHtmlContent());
		
		if (parsed.getCalendarMethod() != null) {
			builder.withCalendarText(CalendarMethod.valueOf(parsed.getCalendarMethod()), assumeNonNull(parsed.getCalendarContent()));
		}
		
		for (final Map.Entry<String, DataSource> cid : parsed.getCidMap().entrySet()) {
			final String cidName = checkNonEmptyArgument(cid.getKey(), "cid.key");
			builder.withEmbeddedImage(extractCID(cidName), cid.getValue());
		}
		for (final Map.Entry<String, DataSource> attachment : parsed.getAttachmentList().entrySet()) {
			builder.withAttachment(extractCID(attachment.getKey()), attachment.getValue());
		}
	}

	private static Session createDummySession() {
		return Session.getDefaultInstance(new Properties());
	}
}