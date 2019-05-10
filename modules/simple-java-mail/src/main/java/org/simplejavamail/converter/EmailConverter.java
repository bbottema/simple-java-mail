package org.simplejavamail.converter;

import org.simplejavamail.api.email.AttachmentResource;
import org.simplejavamail.api.email.CalendarMethod;
import org.simplejavamail.api.email.Email;
import org.simplejavamail.api.email.EmailPopulatingBuilder;
import org.simplejavamail.api.email.OriginalSmimeDetails;
import org.simplejavamail.api.email.OriginalSmimeDetails.SmimeMode;
import org.simplejavamail.api.internal.outlooksupport.model.EmailFromOutlookMessage;
import org.simplejavamail.api.internal.outlooksupport.model.OutlookMessage;
import org.simplejavamail.api.internal.outlooksupport.model.OutlookSmime.OutlookSmimeApplicationSmime;
import org.simplejavamail.api.internal.outlooksupport.model.OutlookSmime.OutlookSmimeMultipartSigned;
import org.simplejavamail.api.mailer.config.Pkcs12Config;
import org.simplejavamail.converter.internal.mimemessage.MimeMessageParser;
import org.simplejavamail.converter.internal.mimemessage.MimeMessageParser.ParsedMimeMessageComponents;
import org.simplejavamail.converter.internal.mimemessage.MimeMessageProducerHelper;
import org.simplejavamail.email.EmailBuilder;
import org.simplejavamail.email.internal.EmailStartingBuilderImpl;
import org.simplejavamail.email.internal.InternalEmailPopulatingBuilder;
import org.simplejavamail.internal.modules.ModuleLoader;
import org.simplejavamail.internal.modules.SMIMEModule;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.activation.DataSource;
import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import javax.mail.MessagingException;
import javax.mail.Session;
import javax.mail.internet.ContentType;
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
import java.util.List;
import java.util.Map;
import java.util.Properties;

import static java.lang.String.format;
import static java.nio.charset.StandardCharsets.UTF_8;
import static org.simplejavamail.converter.EmailConverterException.ERROR_READING_SMIME_CONTENT_TYPE;
import static org.simplejavamail.internal.util.MiscUtil.extractCID;
import static org.simplejavamail.internal.util.MiscUtil.readInputStreamToString;
import static org.simplejavamail.internal.util.Preconditions.assumeNonNull;
import static org.simplejavamail.internal.util.Preconditions.checkNonEmptyArgument;
import static org.simplejavamail.internal.util.SimpleOptional.ofNullable;
import static org.simplejavamail.internal.util.SmimeRecognitionUtil.isSmimeContentType;

/**
 * Utility to help convert {@link org.simplejavamail.api.email.Email} instances to other formats (MimeMessage, EML etc.) and vice versa.
 *
 * If you use the Outlook parsing API, make sure you load the following dependency: <em>org.simplejavamail::outlook-message-parser</em>
 */
@SuppressWarnings("WeakerAccess")
public final class EmailConverter {

	private static final Logger LOGGER = LoggerFactory.getLogger(EmailConverter.class);
	
	private static final PathMatcher EML_PATH_MATCHER = FileSystems.getDefault().getPathMatcher("glob:**/*.eml");
	private static final PathMatcher MSG_PATH_MATCHER = FileSystems.getDefault().getPathMatcher("glob:**/*.msg");

	private EmailConverter() {
		// util / helper class
	}

	/*
		To Email instance
	 */

	/**
	 * Delegates to {@link #mimeMessageToEmail(MimeMessage, Pkcs12Config)}.
	 */
	@Nonnull
	public static Email mimeMessageToEmail(@Nonnull final MimeMessage mimeMessage) {
		return mimeMessageToEmail(mimeMessage, null);
	}

	/**
	 * Delegates to {@link #mimeMessageToEmailBuilder(MimeMessage, Pkcs12Config)}.
	 */
	@Nonnull
	public static Email mimeMessageToEmail(@Nonnull final MimeMessage mimeMessage, @Nullable final Pkcs12Config pkcs12Config) {
		return mimeMessageToEmailBuilder(mimeMessage, pkcs12Config).buildEmail();
	}

	/**
	 * Delegates to {@link #mimeMessageToEmailBuilder(MimeMessage, Pkcs12Config)}.
	 */
	@Nonnull
	public static EmailPopulatingBuilder mimeMessageToEmailBuilder(@Nonnull final MimeMessage mimeMessage) {
		return mimeMessageToEmailBuilder(mimeMessage, null);
	}

	/**
	 * @param mimeMessage The MimeMessage from which to create the {@link Email}.
	 * @param pkcs12Config Private key store for decrypting S/MIME encrypted attachments
	 *                        (only needed when the message is encrypted rather than just signed).
	 */
	@Nonnull
	public static EmailPopulatingBuilder mimeMessageToEmailBuilder(@Nonnull final MimeMessage mimeMessage, @Nullable final Pkcs12Config pkcs12Config) {
		checkNonEmptyArgument(mimeMessage, "mimeMessage");
		final EmailPopulatingBuilder builder = EmailBuilder.ignoringDefaults().startingBlank();
		final ParsedMimeMessageComponents parsed = MimeMessageParser.parseMimeMessage(mimeMessage);
		decryptAttachments(buildEmailFromMimeMessage(builder, parsed), mimeMessage, pkcs12Config);

		if (builder.getOriginalSmimeDetails() != null) {
			((InternalEmailPopulatingBuilder) builder).complementWithSignedBy(checkSignedBy(mimeMessage));
			if (builder.getOriginalSmimeDetails().getSmimeMode() == SmimeMode.SIGNED) {
				boolean signatureValid = checkSignature(mimeMessage, builder.getOriginalSmimeDetails());
				((InternalEmailPopulatingBuilder) builder).complementWithSignatureValid(signatureValid);
			}
		}
		return builder;
	}

	/**
	 * Delegates to {@link #outlookMsgToEmail(String, Pkcs12Config)}.
	 *
	 * @param msgFile The content of an Outlook (.msg) message from which to create the {@link Email}.
	 */
	@Nonnull
	public static Email outlookMsgToEmail(@Nonnull final String msgFile) {
		return outlookMsgToEmail(msgFile, null);
	}

	/**
	 * @param msgFile The content of an Outlook (.msg) message from which to create the {@link Email}.
	 * @param pkcs12Config Private key store for decrypting S/MIME encrypted attachments
	 *                        (only needed when the message is encrypted rather than just signed).
	 */
	@SuppressWarnings("deprecation")
	@Nonnull
	public static Email outlookMsgToEmail(@Nonnull final String msgFile, @Nullable final Pkcs12Config pkcs12Config) {
		checkNonEmptyArgument(msgFile, "msgFile");
		EmailFromOutlookMessage result = ModuleLoader.loadOutlookModule().outlookMsgToEmailBuilder(msgFile, new EmailStartingBuilderImpl());
		return decryptAttachments(result.getEmailBuilder(), result.getOutlookMessage(), pkcs12Config)
				.buildEmail();
	}

	/**
	 * Delegates to {@link #outlookMsgToEmailBuilder(File)} and then builds and returns the email.
	 *
	 * @param msgFile The content of an Outlook (.msg) message from which to create the {@link Email}.
	 */
	@Nonnull
	public static Email outlookMsgToEmail(@Nonnull final File msgFile) {
		return outlookMsgToEmailBuilder(msgFile).buildEmail();
	}

	/**
	 * Delegates to {@link #outlookMsgToEmailBuilder(File, Pkcs12Config)} and then builds and returns the email.
	 *
	 * @param msgFile The content of an Outlook (.msg) message from which to create the {@link Email}.
	 */
	@SuppressWarnings("unused")
	@Nonnull
	public static Email outlookMsgToEmail(@Nonnull final File msgFile, @Nullable final Pkcs12Config pkcs12Config) {
		return outlookMsgToEmailBuilder(msgFile, pkcs12Config).buildEmail();
	}

	/**
	 * Delegates to {@link #outlookMsgToEmailBuilder(File, Pkcs12Config)}.
	 *
	 * @param msgFile The content of an Outlook (.msg) message from which to create the {@link Email}.
	 */
	@Nonnull
	public static EmailPopulatingBuilder outlookMsgToEmailBuilder(@Nonnull final File msgFile) {
		return outlookMsgToEmailBuilder(msgFile, null);
	}

	/**
	 * @param msgFile The content of an Outlook (.msg) message from which to create the {@link Email}.
	 * @param pkcs12Config Private key store for decrypting S/MIME encrypted attachments
	 *                        (only needed when the message is encrypted rather than just signed).
	 */
	@SuppressWarnings({ "deprecation" })
	@Nonnull
	public static EmailPopulatingBuilder outlookMsgToEmailBuilder(@Nonnull final File msgFile, @Nullable final Pkcs12Config pkcs12Config) {
		checkNonEmptyArgument(msgFile, "msgFile");
		if (!MSG_PATH_MATCHER.matches(msgFile.toPath())) {
			throw new EmailConverterException(format(EmailConverterException.FILE_NOT_RECOGNIZED_AS_OUTLOOK, msgFile));
		}
		EmailFromOutlookMessage result = ModuleLoader.loadOutlookModule()
				.outlookMsgToEmailBuilder(msgFile, new EmailStartingBuilderImpl());
		return decryptAttachments(result.getEmailBuilder(), result.getOutlookMessage(), pkcs12Config);
	}

	/**
	 * Delegates to {@link #outlookMsgToEmail(InputStream, Pkcs12Config)}.
	 */
	@Nonnull
	public static Email outlookMsgToEmail(@Nonnull final InputStream msgInputStream) {
		return outlookMsgToEmail(msgInputStream, null);
	}

	/**
	 * Delegates to {@link #outlookMsgToEmailBuilder(InputStream, Pkcs12Config)}.
	 */
	@Nonnull
	public static Email outlookMsgToEmail(@Nonnull final InputStream msgInputStream, @Nullable final Pkcs12Config pkcs12Config) {
		return outlookMsgToEmailBuilder(msgInputStream, pkcs12Config).getEmailBuilder().buildEmail();
	}

	/**
	 * Delegates to {@link #outlookMsgToEmailBuilder(InputStream, Pkcs12Config)}.
	 */
	@SuppressWarnings("deprecation")
	@Nonnull
	public static EmailFromOutlookMessage outlookMsgToEmailBuilder(@Nonnull final InputStream msgInputStream) {
		return outlookMsgToEmailBuilder(msgInputStream, null);
	}

	/**
	 * @param msgInputStream The content of an Outlook (.msg) message from which to create the {@link Email}.
	 */
	@SuppressWarnings("deprecation")
	@Nonnull
	public static EmailFromOutlookMessage outlookMsgToEmailBuilder(@Nonnull final InputStream msgInputStream, @Nullable final Pkcs12Config pkcs12Config) {
		EmailFromOutlookMessage fromMsgBuilder = ModuleLoader.loadOutlookModule().outlookMsgToEmailBuilder(msgInputStream, new EmailStartingBuilderImpl());
		decryptAttachments(fromMsgBuilder.getEmailBuilder(), fromMsgBuilder.getOutlookMessage(), pkcs12Config)
				.buildEmail();
		return fromMsgBuilder;
	}

	/**
	 * Delegates to {@link #emlToEmail(InputStream, Pkcs12Config)}.
	 */
	@Nonnull
	public static Email emlToEmail(@Nonnull final InputStream emlInputStream) {
		return emlToEmail(emlInputStream, null);
	}

	/**
	 * Delegates to {@link #emlToEmailBuilder(InputStream, Pkcs12Config)} with the full string value read from the given <code>InputStream</code>.
	 */
	@Nonnull
	public static Email emlToEmail(@Nonnull final InputStream emlInputStream, @Nullable final Pkcs12Config pkcs12Config) {
		return emlToEmailBuilder(emlInputStream, pkcs12Config).buildEmail();
	}

	/**
	 * Delegates to {@link #emlToEmail(String, Pkcs12Config)}.
	 */
	@Nonnull
	public static Email emlToEmail(@Nonnull final String eml) {
		return emlToEmail(eml, null);
	}

	/**
	 * Delegates to {@link #emlToEmailBuilder(String, Pkcs12Config)}.
	 */
	@Nonnull
	public static Email emlToEmail(@Nonnull final String eml, @Nullable final Pkcs12Config pkcs12Config) {
		return emlToEmailBuilder(eml, pkcs12Config).buildEmail();
	}

	/**
	 * Delegates to {@link #emlToEmail(File, Pkcs12Config)}.
	 */
	@Nonnull
	public static Email emlToEmail(@Nonnull final File emlFile) {
		return emlToEmail(emlFile, null);
	}

	/**
	 * Delegates to {@link #emlToEmailBuilder(File, Pkcs12Config)}.
	 */
	@Nonnull
	public static Email emlToEmail(@Nonnull final File emlFile, @Nullable final Pkcs12Config pkcs12Config) {
		return emlToEmailBuilder(emlFile, pkcs12Config).buildEmail();
	}

	/**
	 * Delegates to {@link #emlToEmailBuilder(File, Pkcs12Config)}.
	 */
	@Nonnull
	public static EmailPopulatingBuilder emlToEmailBuilder(@Nonnull final File emlFile) {
		return emlToEmailBuilder(emlFile, null);
	}

	/**
	 * Delegates to {@link #emlToMimeMessage(File)} and then {@link #mimeMessageToEmailBuilder(MimeMessage, Pkcs12Config)}.
	 */
	@Nonnull
	public static EmailPopulatingBuilder emlToEmailBuilder(@Nonnull final File emlFile, @Nullable final Pkcs12Config pkcs12Config) {
		return mimeMessageToEmailBuilder(emlToMimeMessage(emlFile), pkcs12Config);
	}

	/**
	 * Delegates to {@link #emlToEmailBuilder(InputStream, Pkcs12Config)}.
	 */
	@Nonnull
	public static EmailPopulatingBuilder emlToEmailBuilder(@Nonnull final InputStream emlInputStream) {
		return emlToEmailBuilder(emlInputStream, null);
	}

	/**
	 * Delegates to {@link #emlToEmail(String)} with the full string value read from the given <code>InputStream</code>.
	 */
	@Nonnull
	public static EmailPopulatingBuilder emlToEmailBuilder(@Nonnull final InputStream emlInputStream, @Nullable final Pkcs12Config pkcs12Config) {
		try {
			String emlStr = readInputStreamToString(checkNonEmptyArgument(emlInputStream, "emlInputStream"), UTF_8);
			return emlToEmailBuilder(emlStr, pkcs12Config);
		} catch (IOException e) {
			throw new EmailConverterException(EmailConverterException.ERROR_READING_EML_INPUTSTREAM, e);
		}
	}

	/**
	 * Delegates to {@link #emlToEmailBuilder(String, Pkcs12Config)}.
	 */
	@Nonnull
	public static EmailPopulatingBuilder emlToEmailBuilder(@Nonnull final String eml) {
		return emlToEmailBuilder(eml, null);
	}

	/**
	 * Delegates to {@link #emlToMimeMessage(String, Session)} using a dummy {@link Session} instance and passes the result to {@link
	 * #mimeMessageToEmailBuilder(MimeMessage, Pkcs12Config)}.
	 */
	@Nonnull
	public static EmailPopulatingBuilder emlToEmailBuilder(@Nonnull final String eml, @Nullable final Pkcs12Config pkcs12Config) {
		final MimeMessage mimeMessage = emlToMimeMessage(checkNonEmptyArgument(eml, "eml"), createDummySession());
		return mimeMessageToEmailBuilder(mimeMessage, pkcs12Config);
	}

	/*
		To MimeMessage instance
	 */

	/**
	 * Delegates to {@link #outlookMsgToMimeMessage(String, Pkcs12Config)}.
	 */
	@Nonnull
	public static MimeMessage outlookMsgToMimeMessage(@Nonnull final String msgFile) {
		return outlookMsgToMimeMessage(msgFile, null);
	}

	/**
	 * @return Result of {@link #outlookMsgToEmail(String, Pkcs12Config)} and {@link #emailToMimeMessage(Email)}.
	 */
	@Nonnull
	public static MimeMessage outlookMsgToMimeMessage(@Nonnull final String msgFile, @Nullable final Pkcs12Config pkcs12Config) {
		checkNonEmptyArgument(msgFile, "outlookMsgData");
		return emailToMimeMessage(outlookMsgToEmail(msgFile, pkcs12Config));
	}

	/**
	 * Delegates to {@link #outlookMsgToMimeMessage(File, Pkcs12Config)}.
	 */
	@Nonnull
	public static MimeMessage outlookMsgToMimeMessage(@Nonnull final File outlookMsgFile) {
		return outlookMsgToMimeMessage(outlookMsgFile, null);
	}

	/**
	 * @return Result of {@link #outlookMsgToEmail(File, Pkcs12Config)} and {@link #emailToMimeMessage(Email)}.
	 */
	@Nonnull
	public static MimeMessage outlookMsgToMimeMessage(@Nonnull final File outlookMsgFile, @Nullable final Pkcs12Config pkcs12Config) {
		checkNonEmptyArgument(outlookMsgFile, "outlookMsgFile");
		return emailToMimeMessage(outlookMsgToEmail(outlookMsgFile, pkcs12Config));
	}

	/**
	 * Delegates to {@link #outlookMsgToMimeMessage(InputStream, Pkcs12Config)}.
	 */
	@Nonnull
	public static MimeMessage outlookMsgToMimeMessage(@Nonnull final InputStream outlookMsgInputStream) {
		return outlookMsgToMimeMessage(outlookMsgInputStream, null);
	}

	/**
	 * @return Result of {@link #outlookMsgToEmail(InputStream, Pkcs12Config)} and {@link #emailToMimeMessage(Email)}.
	 */
	@Nonnull
	public static MimeMessage outlookMsgToMimeMessage(@Nonnull final InputStream outlookMsgInputStream, @Nullable final Pkcs12Config pkcs12Config) {
		checkNonEmptyArgument(outlookMsgInputStream, "outlookMsgInputStream");
		return emailToMimeMessage(outlookMsgToEmail(outlookMsgInputStream, pkcs12Config));
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
	 * Refer to {@link MimeMessageProducerHelper#produceMimeMessage(Email, Session)}.
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
	 */
	@Nonnull
	public static MimeMessage emlToMimeMessage(@Nonnull final File emlFile) {
		return emlToMimeMessage(emlFile, createDummySession());
	}

	/**
	 * Delegates to {@link #emlToMimeMessage(InputStream, Session)}.
	 */
	public static MimeMessage emlToMimeMessage(@Nonnull final File emlFile, @Nonnull final Session session) {
		if (!EML_PATH_MATCHER.matches(emlFile.toPath())) {
			throw new EmailConverterException(format(EmailConverterException.FILE_NOT_RECOGNIZED_AS_EML, emlFile));
		}
		try {
			return emlToMimeMessage(new FileInputStream(checkNonEmptyArgument(emlFile, "emlFile")), session);
		} catch (final FileNotFoundException e) {
			throw new EmailConverterException(format(EmailConverterException.PARSE_ERROR_EML_FROM_FILE, e.getMessage()), e);
		}
	}

	/**
	 * Delegates to {@link #emlToMimeMessage(InputStream, Session)} using {@link #createDummySession()}.
	 */
	@Nonnull
	public static MimeMessage emlToMimeMessage(@Nonnull final InputStream inputStream) {
		return emlToMimeMessage(inputStream, createDummySession());
	}

	/**
	 * Relies on JavaMail's native parser of EML data, {@link MimeMessage#MimeMessage(Session, InputStream)}.
	 *
	 * @see MimeMessage#MimeMessage(Session, InputStream)
	 */
	@Nonnull
	public static MimeMessage emlToMimeMessage(@Nonnull final InputStream inputStream, @Nonnull final Session session) {
		try {
			return new MimeMessage(session, inputStream);
		} catch (final MessagingException e) {
			throw new EmailConverterException(format(EmailConverterException.PARSE_ERROR_EML_FROM_STREAM, e.getMessage()), e);
		}
	}

	/**
	 * Delegates to {@link #emlToMimeMessage(String, Session)} with an empty {@link Session} instance.
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
			throw new EmailConverterException(format(EmailConverterException.PARSE_ERROR_EML_FROM_STREAM, e.getMessage()), e);
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
	 * Delegates to {@link #outlookMsgToEML(String, Pkcs12Config)}.
	 */
	@Nonnull
	public static String outlookMsgToEML(@Nonnull final String msgFile) {
		return outlookMsgToEML(msgFile, null);
	}

	/**
	 * @return Result of {@link #outlookMsgToEmail(String, Pkcs12Config)} and {@link #emailToEML(Email)}
	 */
	@Nonnull
	public static String outlookMsgToEML(@Nonnull final String msgFile, @Nullable final Pkcs12Config pkcs12Config) {
		checkNonEmptyArgument(msgFile, "outlookMsgData");
		return emailToEML(outlookMsgToEmail(msgFile, pkcs12Config));
	}

	/**
	 * Delegates to {@link #outlookMsgToEML(File, Pkcs12Config)}.
	 */
	@Nonnull
	public static String outlookMsgToEML(@Nonnull final File outlookMsgFile) {
		return outlookMsgToEML(outlookMsgFile, null);
	}

	/**
	 * @return Result of {@link #outlookMsgToEmail(File, Pkcs12Config)} and {@link #emailToEML(Email)}
	 */
	@Nonnull
	public static String outlookMsgToEML(@Nonnull final File outlookMsgFile, @Nullable final Pkcs12Config pkcs12Config) {
		checkNonEmptyArgument(outlookMsgFile, "outlookMsgFile");
		return emailToEML(outlookMsgToEmail(outlookMsgFile, pkcs12Config));
	}

	/**
	 * Delegates to {@link #outlookMsgToEML(InputStream, Pkcs12Config)}.
	 */
	@Nonnull
	public static String outlookMsgToEML(@Nonnull final InputStream outlookMsgInputStream) {
		return outlookMsgToEML(outlookMsgInputStream, null);
	}

	/**
	 * @return Result of {@link #outlookMsgToEmail(InputStream, Pkcs12Config)} and {@link #emailToEML(Email)}
	 */
	@Nonnull
	public static String outlookMsgToEML(@Nonnull final InputStream outlookMsgInputStream, @Nullable final Pkcs12Config pkcs12Config) {
		checkNonEmptyArgument(outlookMsgInputStream, "outlookMsgInputStream");
		return emailToEML(outlookMsgToEmail(outlookMsgInputStream, pkcs12Config));
	}

	/*
		Helpers
	 */

	private static EmailPopulatingBuilder buildEmailFromMimeMessage(@Nonnull final EmailPopulatingBuilder builder, @Nonnull final ParsedMimeMessageComponents parsed) {
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
		return builder;
	}

	private static Session createDummySession() {
		return Session.getDefaultInstance(new Properties());
	}

	@Nonnull
	@SuppressWarnings("deprecation")
	private static EmailPopulatingBuilder decryptAttachments(@Nonnull final EmailPopulatingBuilder builder, @Nonnull final OutlookMessage outlookMessage, @Nullable final Pkcs12Config pkcs12Config) {
		OriginalSmimeDetails messageSmimeDetails = null;
		if (outlookMessage.getSmimeMime() instanceof OutlookSmimeApplicationSmime) {
			final OutlookSmimeApplicationSmime s = (OutlookSmimeApplicationSmime) outlookMessage.getSmimeMime();
			messageSmimeDetails = OriginalSmimeDetails.builder()
					.smimeMime(s.getSmimeMime())
					.smimeType(s.getSmimeType())
					.smimeName(s.getSmimeName())
					.build();
		} else if (outlookMessage.getSmimeMime() instanceof OutlookSmimeMultipartSigned) {
			final OutlookSmimeMultipartSigned s = (OutlookSmimeMultipartSigned) outlookMessage.getSmimeMime();
			messageSmimeDetails = OriginalSmimeDetails.builder()
					.smimeMime(s.getSmimeMime())
					.smimeProtocol(s.getSmimeProtocol())
					.smimeMicalg(s.getSmimeMicalg())
					.build();
		}

		decryptAttachments(builder, messageSmimeDetails, pkcs12Config);

		if (builder.getOriginalSmimeDetails() != null &&
				builder.getOriginalSmimeDetails().getSmimeMode() == SmimeMode.SIGNED) {
			// this is the only way for Outlook messages to know a valid signature was included
			((InternalEmailPopulatingBuilder) builder)
					.complementWithSignatureValid(builder.getSmimeSignedEmail() != null);
		}

		return builder;
	}

	@SuppressWarnings({ "deprecation" })
	private static void decryptAttachments(
			@Nonnull final EmailPopulatingBuilder emailBuilder,
			@Nonnull final MimeMessage mimeMessage,
			@Nullable final Pkcs12Config pkcs12Config) {
		OriginalSmimeDetails originalSmimeDetails = null;

		try {
			if (mimeMessage.getHeader("Content-Type", null) != null) {
				ContentType ct = new ContentType(mimeMessage.getHeader("Content-Type", null));
				if (isSmimeContentType(ct)) {
					originalSmimeDetails = OriginalSmimeDetails.builder()
							.smimeMime(ct.getBaseType())
							.smimeType(ct.getParameter("smime-type"))
							.smimeName(ct.getParameter("name"))
							.smimeProtocol(ct.getParameter("protocol"))
							.smimeMicalg(ct.getParameter("micalg"))
							.build();
					OriginalSmimeDetails relevantDetails = ofNullable(emailBuilder.getOriginalSmimeDetails())
							.orElse(OriginalSmimeDetails.EMPTY)
							.completeWith(originalSmimeDetails);
					((InternalEmailPopulatingBuilder) emailBuilder).withOriginalSmimeDetails(relevantDetails);
				}
			}
		} catch (MessagingException e) {
			throw new EmailConverterException(ERROR_READING_SMIME_CONTENT_TYPE, e);
		}

		decryptAttachments(emailBuilder, originalSmimeDetails, pkcs12Config);
	}

	private static boolean checkSignature(@Nonnull final MimeMessage mimeMessage, @Nullable final OriginalSmimeDetails messageSmimeDetails) {
		if (messageSmimeDetails != null && ModuleLoader.smimeModuleAvailable()) {
			LOGGER.debug("verifying signed mimemessage...");
			final SMIMEModule smimeModule = ModuleLoader.loadSmimeModule();
			final boolean validSignature = smimeModule.verifyValidSignature(mimeMessage, messageSmimeDetails);
			if (!validSignature) {
				LOGGER.warn("Message contains invalid S/MIME signature! Assume this emal has been tampered with.");
			}
			return validSignature;
		}
		return false;
	}

	private static String checkSignedBy(@Nonnull final MimeMessage mimeMessage) {
		if (ModuleLoader.smimeModuleAvailable()) {
			LOGGER.debug("checking who signed this message...");
			return ModuleLoader.loadSmimeModule().getSignedByAddress(mimeMessage);
		}
		return null;
	}

	@Nonnull
	private static EmailPopulatingBuilder decryptAttachments(
			@Nonnull final EmailPopulatingBuilder emailBuilder,
			@Nullable final OriginalSmimeDetails messageSmimeDetails,
			@Nullable final Pkcs12Config pkcs12Config) {
		if (ModuleLoader.smimeModuleAvailable()) {
			final InternalEmailPopulatingBuilder internalEmailBuilder = (InternalEmailPopulatingBuilder) emailBuilder;

			LOGGER.debug("checking for S/MIME signed / encrypted attachments...");
			final SMIMEModule smimeModule = ModuleLoader.loadSmimeModule();
			List<AttachmentResource> decryptedAttachments = smimeModule
					.decryptAttachments(emailBuilder.getAttachments(), pkcs12Config, messageSmimeDetails);
			internalEmailBuilder.withDecryptedAttachments(decryptedAttachments);

			if (emailBuilder.getAttachments().size() == 1) {
				final AttachmentResource onlyAttachment = emailBuilder.getAttachments().get(0);
				final AttachmentResource onlyAttachmentDecrypted = internalEmailBuilder.getDecryptedAttachments().get(0);
				if (smimeModule.isSmimeAttachment(onlyAttachment) && isMimeMessageAttachment(onlyAttachmentDecrypted)) {
					internalEmailBuilder.withOriginalSmimeDetails(determineSmimeDetails(messageSmimeDetails, smimeModule, onlyAttachment));
					internalEmailBuilder.withSmimeSignedEmail(emlToEmail(onlyAttachmentDecrypted.getDataSourceInputStream()));
				}
			}
		}
		return emailBuilder;
	}

	private static boolean isMimeMessageAttachment(final AttachmentResource attachment) {
		return attachment.getDataSource().getContentType().equals("message/rfc822");
	}

	@Nonnull
	@SuppressWarnings("deprecation")
	private static OriginalSmimeDetails determineSmimeDetails(@Nullable final OriginalSmimeDetails messageSmimeDetails, final SMIMEModule smimeModule,
			final AttachmentResource attachment) {
		LOGGER.debug("Single S/MIME signed / encrypted attachment found; assuming the attachment is the message "
				+ "body, a record of the original S/MIME details will be stored on the Email root...");
		final OriginalSmimeDetails attachmentSmimeDetails = smimeModule.getSmimeDetails(attachment);
		return ofNullable(messageSmimeDetails)
				.orElse(attachmentSmimeDetails)
				.completeWith(attachmentSmimeDetails);
	}
}