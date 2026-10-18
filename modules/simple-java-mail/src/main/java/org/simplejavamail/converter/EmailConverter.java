package org.simplejavamail.converter;

import jakarta.mail.MessagingException;
import jakarta.mail.Session;
import jakarta.mail.internet.MimeMessage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.UnsupportedEncodingException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Properties;
import lombok.experimental.UtilityClass;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.simplejavamail.api.SimpleJavaMail;
import org.simplejavamail.api.converter.ConfiguredEmailConverter;
import org.simplejavamail.api.email.Email;
import org.simplejavamail.api.email.EmailPopulatingBuilder;
import org.simplejavamail.api.email.EmailStartingBuilder;
import org.simplejavamail.api.email.config.OpenPgpReceiveConfig;
import org.simplejavamail.api.mailer.config.EmailGovernance;
import org.simplejavamail.api.mailer.config.Pkcs12Config;
import org.simplejavamail.api.outlook.OutlookEmailConversionResult;
import org.simplejavamail.converter.internal.EmailConverterException;
import org.simplejavamail.converter.internal.ConfiguredEmailConverterImpl;
import org.simplejavamail.email.internal.InternalEmail;
import org.simplejavamail.internal.util.JakartaMailImplementation;

import static java.lang.String.format;
import static java.nio.charset.StandardCharsets.UTF_8;
import static org.simplejavamail.internal.util.Preconditions.checkNonEmptyArgument;
import static org.simplejavamail.mailer.internal.EmailGovernanceImpl.NO_GOVERNANCE;

/**
 * Utility to help convert {@link org.simplejavamail.api.email.Email} instances to other formats (MimeMessage, EML etc.) and vice versa.
 * Static conversion methods that produce an email builder use the conventional immutable configuration from {@link SimpleJavaMail#fromDefaults()}.
 * Use {@link SimpleJavaMail#converter()} when conversion must use an explicit factory snapshot.
 * <br>
 * For Outlook conversion, add <em>org.simplejavamail:outlook-module</em> using the same version as Simple Java Mail.
 */
@SuppressWarnings("WeakerAccess")
@UtilityClass
public class EmailConverter {

	/**
	 * Delegates to {@link #mimeMessageToEmail(MimeMessage, Pkcs12Config)}.
	 */
	@NotNull
	public static Email mimeMessageToEmail(@NotNull final MimeMessage mimeMessage) {
		return mimeMessageToEmail(mimeMessage, null);
	}

	/**
	 * Delegates to {@link #mimeMessageToEmailBuilder(MimeMessage, Pkcs12Config)}.
	 */
	@NotNull
	public static Email mimeMessageToEmail(@NotNull final MimeMessage mimeMessage, @Nullable final Pkcs12Config pkcs12Config) {
		return mimeMessageToEmailBuilder(mimeMessage, pkcs12Config).buildEmail();
	}

	/**
	 * Delegates to {@link #mimeMessageToEmailBuilder(MimeMessage, Pkcs12Config, boolean)}.
	 */
	@NotNull
	public static Email mimeMessageToEmail(@NotNull final MimeMessage mimeMessage, @Nullable final Pkcs12Config pkcs12Config, final boolean fetchAttachmentData) {
		return mimeMessageToEmailBuilder(mimeMessage, pkcs12Config, fetchAttachmentData).buildEmail();
	}

	/** Converts a message after OpenPGP verification/decryption and optional S/MIME processing. */
	@NotNull
	public static Email mimeMessageToEmail(@NotNull final MimeMessage mimeMessage,
			@Nullable final Pkcs12Config pkcs12Config,
			@Nullable final OpenPgpReceiveConfig openPgpReceiveConfig) {
		return mimeMessageToEmailBuilder(mimeMessage, pkcs12Config, openPgpReceiveConfig, EmailStartingBuilder.DEFAULT_FETCH_ATTACHMENT_DATA).buildEmail();
	}

	/**
	 * Delegates to {@link #mimeMessageToEmailBuilder(MimeMessage, Pkcs12Config)}.
	 */
	@NotNull
	public static EmailPopulatingBuilder mimeMessageToEmailBuilder(@NotNull final MimeMessage mimeMessage) {
		return mimeMessageToEmailBuilder(mimeMessage, null);
	}

	/**
	 * Delegates to {@link #mimeMessageToEmailBuilder(MimeMessage, Pkcs12Config, boolean)}.
	 */
	@NotNull
	public static EmailPopulatingBuilder mimeMessageToEmailBuilder(@NotNull final MimeMessage mimeMessage, @Nullable final Pkcs12Config pkcs12Config) {
		return mimeMessageToEmailBuilder(mimeMessage, pkcs12Config, EmailStartingBuilder.DEFAULT_FETCH_ATTACHMENT_DATA);
	}

	/**
	 * @param mimeMessage The MimeMessage from which to create the {@link Email}.
	 * @param pkcs12Config Private key store for decrypting S/MIME encrypted attachments
	 *                        (only needed when the message is encrypted rather than just signed).
	 * @param fetchAttachmentData When false only the names of the attachments are retrieved but no data
	 */
	@NotNull
	public static EmailPopulatingBuilder mimeMessageToEmailBuilder(@NotNull final MimeMessage mimeMessage,
			@Nullable final Pkcs12Config pkcs12Config, final boolean fetchAttachmentData) {
		return mimeMessageToEmailBuilder(mimeMessage, pkcs12Config, null, fetchAttachmentData);
	}

	@NotNull
	public static EmailPopulatingBuilder mimeMessageToEmailBuilder(@NotNull final MimeMessage mimeMessage,
			@Nullable final Pkcs12Config pkcs12Config,
			@Nullable final OpenPgpReceiveConfig openPgpReceiveConfig,
			final boolean fetchAttachmentData) {
		return conventionalConverter()
				.mimeMessageToEmailBuilder(mimeMessage, pkcs12Config, openPgpReceiveConfig, fetchAttachmentData);
	}

	/**
	 * Delegates to {@link #outlookMsgToEmail(String, Pkcs12Config)}.
	 *
	 * @param msgFileName The file name of an Outlook (.msg) message from which to create the {@link Email}.
	 */
	@SuppressWarnings("unused")
	@NotNull
	public static Email outlookMsgToEmail(@NotNull final String msgFileName) {
		return outlookMsgToEmail(msgFileName, null);
	}

	/**
	 * @param msgFileName The file name of an Outlook (.msg) message from which to create the {@link Email}.
	 * @param pkcs12Config Private key store for decrypting S/MIME encrypted attachments
	 *                        (only needed when the message is encrypted rather than just signed).
	 */
	@SuppressWarnings("deprecation")
	@NotNull
	public static Email outlookMsgToEmail(@NotNull final String msgFileName, @Nullable final Pkcs12Config pkcs12Config) {
		return outlookMsgToEmailBuilderWithOutlookData(msgFileName, pkcs12Config).buildEmail();
	}

	/**
	 * Delegates to {@link #outlookMsgToEmailBuilderWithOutlookData(String, Pkcs12Config)}.
	 *
	 * @param msgFileName The file name of an Outlook (.msg) message from which to create the {@link Email}.
	 */
	@NotNull
	public static OutlookEmailConversionResult outlookMsgToEmailBuilderWithOutlookData(@NotNull final String msgFileName) {
		return outlookMsgToEmailBuilderWithOutlookData(msgFileName, null);
	}

	/**
	 * Converts an Outlook {@code .msg} message to an email builder while retaining Outlook-specific source data.
	 *
	 * @param msgFileName The file name of an Outlook (.msg) message from which to create the {@link Email}.
	 * @param pkcs12Config Private key store for decrypting S/MIME encrypted attachments
	 *                        (only needed when the message is encrypted rather than just signed).
	 */
	@SuppressWarnings("deprecation")
	@NotNull
	public static OutlookEmailConversionResult outlookMsgToEmailBuilderWithOutlookData(@NotNull final String msgFileName,
			@Nullable final Pkcs12Config pkcs12Config) {
		return conventionalConverter().outlookMsgToEmailBuilderWithOutlookData(msgFileName, pkcs12Config);
	}

	/**
	 * Delegates to {@link #outlookMsgToEmailBuilder(File)} and then builds and returns the email.
	 *
	 * @param msgFile The content of an Outlook (.msg) message from which to create the {@link Email}.
	 */
	@NotNull
	public static Email outlookMsgToEmail(@NotNull final File msgFile) {
		return outlookMsgToEmailBuilder(msgFile).buildEmail();
	}

	/**
	 * Delegates to {@link #outlookMsgToEmailBuilder(File, Pkcs12Config)} and then builds and returns the email.
	 *
	 * @param msgFile The content of an Outlook (.msg) message from which to create the {@link Email}.
	 */
	@SuppressWarnings("unused")
	@NotNull
	public static Email outlookMsgToEmail(@NotNull final File msgFile, @Nullable final Pkcs12Config pkcs12Config) {
		return outlookMsgToEmailBuilder(msgFile, pkcs12Config).buildEmail();
	}

	/**
	 * Delegates to {@link #outlookMsgToEmailBuilder(File, Pkcs12Config)}.
	 *
	 * @param msgFile The content of an Outlook (.msg) message from which to create the {@link Email}.
	 */
	@NotNull
	public static EmailPopulatingBuilder outlookMsgToEmailBuilder(@NotNull final File msgFile) {
		return outlookMsgToEmailBuilder(msgFile, null);
	}

	/**
	 * @param msgFile The content of an Outlook (.msg) message from which to create the {@link Email}.
	 * @param pkcs12Config Private key store for decrypting S/MIME encrypted attachments
	 *                        (only needed when the message is encrypted rather than just signed).
	 */
	@SuppressWarnings({ "deprecation" })
	@NotNull
	public static EmailPopulatingBuilder outlookMsgToEmailBuilder(@NotNull final File msgFile, @Nullable final Pkcs12Config pkcs12Config) {
		return outlookMsgToEmailBuilderWithOutlookData(msgFile, pkcs12Config).getEmailBuilder();
	}

	/**
	 * Delegates to {@link #outlookMsgToEmailBuilderWithOutlookData(File, Pkcs12Config)}.
	 *
	 * @param msgFile The content of an Outlook (.msg) message from which to create the {@link Email}.
	 */
	@NotNull
	public static OutlookEmailConversionResult outlookMsgToEmailBuilderWithOutlookData(@NotNull final File msgFile) {
		return outlookMsgToEmailBuilderWithOutlookData(msgFile, null);
	}

	/**
	 * Converts an Outlook {@code .msg} message to an email builder while retaining Outlook-specific source data.
	 *
	 * @param msgFile The content of an Outlook (.msg) message from which to create the {@link Email}.
	 * @param pkcs12Config Private key store for decrypting S/MIME encrypted attachments
	 *                        (only needed when the message is encrypted rather than just signed).
	 */
	@SuppressWarnings("deprecation")
	@NotNull
	public static OutlookEmailConversionResult outlookMsgToEmailBuilderWithOutlookData(@NotNull final File msgFile, @Nullable final Pkcs12Config pkcs12Config) {
		return conventionalConverter().outlookMsgToEmailBuilderWithOutlookData(msgFile, pkcs12Config);
	}

	/**
	 * Converts the Outlook MSG file at the supplied path and builds the resulting email. For access to Outlook-specific source data or S/MIME
	 * decryption, use {@link #outlookMsgToEmailBuilderWithOutlookData(Path, Pkcs12Config)}.
	 *
	 * @param msgPath Path to the Outlook MSG file.
	 * @return The converted email.
	 */
	@NotNull
	public static Email outlookMsgToEmail(@NotNull final Path msgPath) {
		return outlookMsgToEmailBuilderWithOutlookData(msgPath).buildEmail();
	}

	/**
	 * Converts the Outlook MSG file at the supplied path to an editable email builder.
	 *
	 * @param msgPath Path to the Outlook MSG file.
	 * @return An editable builder populated from the message.
	 */
	@NotNull
	public static EmailPopulatingBuilder outlookMsgToEmailBuilder(@NotNull final Path msgPath) {
		return outlookMsgToEmailBuilderWithOutlookData(msgPath).getEmailBuilder();
	}

	/**
	 * Delegates to {@link #outlookMsgToEmailBuilderWithOutlookData(Path, Pkcs12Config)} without receive-side S/MIME configuration.
	 *
	 * @param msgPath Path to the Outlook MSG file.
	 * @return The editable email builder together with Outlook-specific source data.
	 */
	@NotNull
	public static OutlookEmailConversionResult outlookMsgToEmailBuilderWithOutlookData(@NotNull final Path msgPath) {
		return outlookMsgToEmailBuilderWithOutlookData(msgPath, null);
	}

	/**
	 * Converts the Outlook MSG file at the supplied path while retaining its Outlook-specific source data. The stream opened for the path is closed before
	 * this method returns.
	 *
	 * @param msgPath Path to the Outlook MSG file.
	 * @param pkcs12Config Private key configuration for S/MIME decryption, or {@code null} when not needed.
	 * @return The editable email builder together with Outlook-specific source data.
	 */
	@NotNull
	public static OutlookEmailConversionResult outlookMsgToEmailBuilderWithOutlookData(@NotNull final Path msgPath,
			@Nullable final Pkcs12Config pkcs12Config) {
		return conventionalConverter().outlookMsgToEmailBuilderWithOutlookData(msgPath, pkcs12Config);
	}

	/**
	 * Delegates to {@link #outlookMsgToEmail(InputStream, Pkcs12Config)}.
	 */
	@SuppressWarnings("unused")
	@NotNull
	public static Email outlookMsgToEmail(@NotNull final InputStream msgInputStream) {
		return outlookMsgToEmail(msgInputStream, null);
	}

	/**
	 * Delegates to {@link #outlookMsgToEmailBuilder(InputStream, Pkcs12Config)}.
	 */
	@NotNull
	public static Email outlookMsgToEmail(@NotNull final InputStream msgInputStream, @Nullable final Pkcs12Config pkcs12Config) {
		return outlookMsgToEmailBuilderWithOutlookData(msgInputStream, pkcs12Config).buildEmail();
	}

	/**
	 * Delegates to {@link #outlookMsgToEmailBuilder(InputStream, Pkcs12Config)}.
	 *
	 * @deprecated use {@link #outlookMsgToEmailBuilderWithOutlookData(InputStream)}.
	 */
	@NotNull
	@Deprecated
	public static OutlookEmailConversionResult outlookMsgToEmailBuilder(@NotNull final InputStream msgInputStream) {
		return outlookMsgToEmailBuilderWithOutlookData(msgInputStream);
	}

	/**
	 * Note: the email builder wrapped by {@link OutlookEmailConversionResult} is set to ignore defaults to stay as close as possible to the original
	 * MimeMessage.
	 * If you wish to use the result to send an email, you might want to first call {@link EmailPopulatingBuilder#ignoringDefaults(boolean)} to set the builder
	 * to use defaults again.
	 *
	 * @param msgInputStream The content of an Outlook (.msg) message from which to create the {@link Email}.
	 * @deprecated use {@link #outlookMsgToEmailBuilderWithOutlookData(InputStream, Pkcs12Config)}.
	 */
	@SuppressWarnings("deprecation")
	@NotNull
	@Deprecated
	public static OutlookEmailConversionResult outlookMsgToEmailBuilder(@NotNull final InputStream msgInputStream, @Nullable final Pkcs12Config pkcs12Config) {
		return outlookMsgToEmailBuilderWithOutlookData(msgInputStream, pkcs12Config);
	}

	/**
	 * Delegates to {@link #outlookMsgToEmailBuilderWithOutlookData(InputStream, Pkcs12Config)}.
	 *
	 * @param msgInputStream The content of an Outlook (.msg) message from which to create the {@link Email}.
	 */
	@NotNull
	public static OutlookEmailConversionResult outlookMsgToEmailBuilderWithOutlookData(@NotNull final InputStream msgInputStream) {
		return outlookMsgToEmailBuilderWithOutlookData(msgInputStream, null);
	}

	/**
	 * Converts an Outlook {@code .msg} message to an email builder while retaining Outlook-specific source data.
	 *
	 * @param msgInputStream The content of an Outlook (.msg) message from which to create the {@link Email}.
	 * @param pkcs12Config Private key store for decrypting S/MIME encrypted attachments
	 *                        (only needed when the message is encrypted rather than just signed).
	 */
	@SuppressWarnings("deprecation")
	@NotNull
	public static OutlookEmailConversionResult outlookMsgToEmailBuilderWithOutlookData(@NotNull final InputStream msgInputStream,
			@Nullable final Pkcs12Config pkcs12Config) {
		return conventionalConverter().outlookMsgToEmailBuilderWithOutlookData(msgInputStream, pkcs12Config);
	}

	/**
	 * Delegates to {@link #emlToEmail(InputStream, Pkcs12Config)}.
	 */
	@NotNull
	public static Email emlToEmail(@NotNull final InputStream emlInputStream) {
		return emlToEmail(emlInputStream, null);
	}

	/**
	 * Delegates to {@link #emlToEmail(InputStream, Pkcs12Config, Session)} using a dummy {@link Session} instance.
	 */
	@NotNull
	public static Email emlToEmail(@NotNull final InputStream emlInputStream, @Nullable final Pkcs12Config pkcs12Config) {
		return emlToEmail(emlInputStream, pkcs12Config, createDummySession());
	}

	/**
	 * Delegates to {@link #emlToEmailBuilder(InputStream, Pkcs12Config)} with the full string value read from the given <code>InputStream</code>.
	 */
	@NotNull
	public static Email emlToEmail(@NotNull final InputStream emlInputStream, @Nullable final Pkcs12Config pkcs12Config, @NotNull final Session session) {
		return emlToEmailBuilder(emlInputStream, pkcs12Config, session).buildEmail();
	}

	/** Reads exact EML bytes before OpenPGP/MIME verification or decryption. */
	@NotNull
	public static Email emlToEmail(@NotNull final InputStream emlInputStream,
			@Nullable final Pkcs12Config pkcs12Config,
			@Nullable final OpenPgpReceiveConfig openPgpReceiveConfig,
			@NotNull final Session session) {
		return emlToEmailBuilder(emlInputStream, pkcs12Config, openPgpReceiveConfig, session).buildEmail();
	}

	@NotNull
	public static Email emlToEmailWithOpenPgp(@NotNull final InputStream emlInputStream,
			@NotNull final OpenPgpReceiveConfig openPgpReceiveConfig) {
		return emlToEmail(emlInputStream, null, openPgpReceiveConfig, createDummySession());
	}

	/**
	 * Delegates to {@link #emlToEmail(String, Pkcs12Config)}.
	 */
	@NotNull
	public static Email emlToEmail(@NotNull final String eml) {
		return emlToEmail(eml, null);
	}

	/**
	 * Delegates to {@link #emlToEmail(String, Pkcs12Config, Session)} using a dummy {@link Session} instance.
	 */
	@NotNull
	public static Email emlToEmail(@NotNull final String eml, @Nullable final Pkcs12Config pkcs12Config) {
		return emlToEmail(eml, pkcs12Config, createDummySession());
	}

	/**
	 * Delegates to {@link #emlToEmailBuilder(String, Pkcs12Config, Session)}.
	 */
	@NotNull
	public static Email emlToEmail(@NotNull final String eml, @Nullable final Pkcs12Config pkcs12Config, @NotNull final Session session) {
		return emlToEmailBuilder(eml, pkcs12Config, session).buildEmail();
	}

	/**
	 * Delegates to {@link #emlToEmail(File, Pkcs12Config)}.
	 */
	@NotNull
	public static Email emlToEmail(@NotNull final File emlFile) {
		return emlToEmail(emlFile, null);
	}

	/**
	 * Delegates to {@link #emlToEmail(File, Pkcs12Config, Session)} using a dummy {@link Session} instance.
	 */
	@NotNull
	public static Email emlToEmail(@NotNull final File emlFile, @Nullable final Pkcs12Config pkcs12Config) {
		return emlToEmail(emlFile, pkcs12Config, createDummySession());
	}

	/**
	 * Delegates to {@link #emlToEmailBuilder(File, Pkcs12Config, Session)}.
	 */
	@NotNull
	public static Email emlToEmail(@NotNull final File emlFile, @Nullable final Pkcs12Config pkcs12Config, @NotNull final Session session) {
		return emlToEmailBuilder(emlFile, pkcs12Config, session).buildEmail();
	}

	/**
	 * Delegates to {@link #emlToEmailBuilder(File, Session)} using a dummy {@link Session} instance.
	 */
	@NotNull
	public static EmailPopulatingBuilder emlToEmailBuilder(@NotNull final File emlFile) {
		return emlToEmailBuilder(emlFile, createDummySession());
	}

	/**
	 * Delegates to {@link #emlToEmailBuilder(File, Pkcs12Config, Session)}.
	 */
	@NotNull
	public static EmailPopulatingBuilder emlToEmailBuilder(@NotNull final File emlFile, @NotNull final Session session) {
		return emlToEmailBuilder(emlFile, null, session);
	}

	/**
	 * Delegates to {@link #emlToEmailBuilder(File, Pkcs12Config, Session)} using a dummy {@link Session} instance.
	 */
	@NotNull
	public static EmailPopulatingBuilder emlToEmailBuilder(@NotNull final File emlFile, @Nullable final Pkcs12Config pkcs12Config) {
		return emlToEmailBuilder(emlFile, pkcs12Config, createDummySession());
	}

	/**
	 * Delegates to {@link #emlToEmailBuilder(InputStream, Pkcs12Config, Session)}.
	 */
	@NotNull
	public static EmailPopulatingBuilder emlToEmailBuilder(@NotNull final File emlFile,
			@Nullable final Pkcs12Config pkcs12Config, @NotNull final Session session) {
		try (final FileInputStream emlInputStream = new FileInputStream(checkNonEmptyArgument(emlFile, "emlFile"))) {
			return emlToEmailBuilder(emlInputStream, pkcs12Config, session);
		} catch (final IOException e) {
			throw new EmailConverterException(format(EmailConverterException.PARSE_ERROR_EML_FROM_FILE, e.getMessage()), e);
		}
	}

	/**
	 * Parses the EML file at the supplied path and builds the resulting email. Use
	 * {@link ConfiguredEmailConverter#fromEml(Path)} when receive-side security configuration or a
	 * specific Session is required.
	 *
	 * @param emlPath Path to the EML file.
	 * @return The converted email.
	 */
	@NotNull
	public static Email emlToEmail(@NotNull final Path emlPath) {
		return emlToEmailBuilder(emlPath).buildEmail();
	}

	/**
	 * Parses the EML file at the supplied path into an editable email builder. The stream opened for the path is closed before this method returns.
	 *
	 * @param emlPath Path to the EML file.
	 * @return An editable builder populated from the message.
	 */
	@NotNull
	public static EmailPopulatingBuilder emlToEmailBuilder(@NotNull final Path emlPath) {
		return conventionalConverter().emlToEmailBuilder(emlPath);
	}

	/**
	 * Delegates to {@link #emlToEmailBuilder(InputStream, Session)} using a dummy {@link Session} instance.
	 */
	@NotNull
	public static EmailPopulatingBuilder emlToEmailBuilder(@NotNull final InputStream emlInputStream) {
		return emlToEmailBuilder(emlInputStream, createDummySession());
	}

	/**
	 * Delegates to {@link #emlToEmailBuilder(InputStream, Pkcs12Config, Session)}.
	 */
	@NotNull
	public static EmailPopulatingBuilder emlToEmailBuilder(@NotNull final InputStream emlInputStream, @NotNull final Session session) {
		return emlToEmailBuilder(emlInputStream, null, session);
	}

	/**
	 * Delegates to {@link #emlToEmailBuilder(InputStream, Pkcs12Config, Session)} using a dummy {@link Session} instance.
	 */
	@NotNull
	public static EmailPopulatingBuilder emlToEmailBuilder(@NotNull final InputStream emlInputStream, @Nullable final Pkcs12Config pkcs12Config) {
		return emlToEmailBuilder(emlInputStream, pkcs12Config, createDummySession());
	}

	/**
	 * Delegates to {@link #emlToEmailBuilder(String, Pkcs12Config, Session)} with the full string value read from the given <code>InputStream</code>.
	 */
	@NotNull
	public static EmailPopulatingBuilder emlToEmailBuilder(@NotNull final InputStream emlInputStream,
			@Nullable final Pkcs12Config pkcs12Config, @NotNull final Session session) {
		return emlToEmailBuilder(emlInputStream, pkcs12Config, null, session);
	}

	@NotNull
	public static EmailPopulatingBuilder emlToEmailBuilder(@NotNull final InputStream emlInputStream,
			@Nullable final Pkcs12Config pkcs12Config,
			@Nullable final OpenPgpReceiveConfig openPgpReceiveConfig,
			@NotNull final Session session) {
		return conventionalConverter()
				.emlToEmailBuilder(emlInputStream, pkcs12Config, openPgpReceiveConfig, session);
	}

	/**
	 * Delegates to {@link #emlToEmailBuilder(String, Session)} using a dummy {@link Session} instance.
	 */
	@NotNull
	public static EmailPopulatingBuilder emlToEmailBuilder(@NotNull final String eml) {
		return emlToEmailBuilder(eml, createDummySession());
	}

	/**
	 * Delegates to {@link #emlToEmailBuilder(String, Pkcs12Config, Session)}.
	 */
	@NotNull
	public static EmailPopulatingBuilder emlToEmailBuilder(@NotNull final String eml, @NotNull final Session session) {
		return emlToEmailBuilder(eml, null, session);
	}

	/**
	 * Delegates to {@link #emlToEmailBuilder(String, Pkcs12Config, Session)} using a dummy {@link Session} instance.
	 */
	@NotNull
	public static EmailPopulatingBuilder emlToEmailBuilder(@NotNull final String eml, @Nullable final Pkcs12Config pkcs12Config) {
		return emlToEmailBuilder(eml, pkcs12Config, createDummySession());
	}

	/**
	 * Delegates to {@link #emlToMimeMessage(String, Session)} using a dummy {@link Session} instance and passes the result to {@link
	 * #mimeMessageToEmailBuilder(MimeMessage, Pkcs12Config)}.
	 */
	@NotNull
	public static EmailPopulatingBuilder emlToEmailBuilder(@NotNull final String eml, @Nullable final Pkcs12Config pkcs12Config, @NotNull final Session session) {
		return emlToEmailBuilder(new ByteArrayInputStream(checkNonEmptyArgument(eml, "eml").getBytes(UTF_8)),
				pkcs12Config, null, session);
	}

	/**
	 * Delegates to {@link #outlookMsgToMimeMessage(String, Pkcs12Config)}.
	 *
	 * @param msgFileName The file name of an Outlook (.msg) message to convert.
	 */
	@NotNull
	public static MimeMessage outlookMsgToMimeMessage(@NotNull final String msgFileName) {
		return outlookMsgToMimeMessage(msgFileName, null);
	}

	/**
	 * @param msgFileName The file name of an Outlook (.msg) message to convert.
	 * @param pkcs12Config Private key store for decrypting S/MIME encrypted attachments
	 *                     (only needed when the message is encrypted rather than just signed).
	 * @return Result of {@link #outlookMsgToEmail(String, Pkcs12Config)} and {@link #emailToMimeMessage(Email)}.
	 */
	@NotNull
	public static MimeMessage outlookMsgToMimeMessage(@NotNull final String msgFileName, @Nullable final Pkcs12Config pkcs12Config) {
		checkNonEmptyArgument(msgFileName, "msgFileName");
		return emailToMimeMessage(outlookMsgToEmail(msgFileName, pkcs12Config));
	}

	/**
	 * Delegates to {@link #outlookMsgToMimeMessage(File, Pkcs12Config)}.
	 */
	@NotNull
	public static MimeMessage outlookMsgToMimeMessage(@NotNull final File outlookMsgFile) {
		return outlookMsgToMimeMessage(outlookMsgFile, null);
	}

	/**
	 * @return Result of {@link #outlookMsgToEmail(File, Pkcs12Config)} and {@link #emailToMimeMessage(Email)}.
	 */
	@NotNull
	public static MimeMessage outlookMsgToMimeMessage(@NotNull final File outlookMsgFile, @Nullable final Pkcs12Config pkcs12Config) {
		checkNonEmptyArgument(outlookMsgFile, "outlookMsgFile");
		return emailToMimeMessage(outlookMsgToEmail(outlookMsgFile, pkcs12Config));
	}

	/**
	 * Delegates to {@link #outlookMsgToMimeMessage(InputStream, Pkcs12Config)}.
	 */
	@NotNull
	public static MimeMessage outlookMsgToMimeMessage(@NotNull final InputStream outlookMsgInputStream) {
		return outlookMsgToMimeMessage(outlookMsgInputStream, null);
	}

	/**
	 * @return Result of {@link #outlookMsgToEmail(InputStream, Pkcs12Config)} and {@link #emailToMimeMessage(Email)}.
	 */
	@NotNull
	public static MimeMessage outlookMsgToMimeMessage(@NotNull final InputStream outlookMsgInputStream, @Nullable final Pkcs12Config pkcs12Config) {
		checkNonEmptyArgument(outlookMsgInputStream, "outlookMsgInputStream");
		return emailToMimeMessage(outlookMsgToEmail(outlookMsgInputStream, pkcs12Config));
	}

	/**
	 * Delegates to {@link #emailToMimeMessage(Email, Session, EmailGovernance)}, using a new empty {@link Session} instance,
	 * and without email governance - but defaults from (system) prorties (files) are still applied, if provided..
	 */
	public static MimeMessage emailToMimeMessage(@NotNull final Email email) {
		return emailToMimeMessage(checkNonEmptyArgument(email, "email"), createDummySession(), NO_GOVERNANCE());
	}

	/**
	 * Delegates to {@link #emailToMimeMessage(Email, Session, EmailGovernance)}, using a new empty {@link Session} instance.
	 */
	public static MimeMessage emailToMimeMessage(@NotNull final Email email, final EmailGovernance emailGovernance) {
		return emailToMimeMessage(checkNonEmptyArgument(email, "email"), createDummySession(), emailGovernance);
	}

	/**
	 * Delegates to {@link #emailToMimeMessage(Email, Session, EmailGovernance)} with no email governance -
	 * but defaults from (system) prorties (files) are still applied, if provided.
	 */
	public static MimeMessage emailToMimeMessage(@NotNull final Email email, @NotNull final Session session) {
		return emailToMimeMessage(email, session, NO_GOVERNANCE());
	}

	/**
	 * Converts composed email through the MIME producer and returns exact EML through its byte-preserving source.
	 */
	public static MimeMessage emailToMimeMessage(@NotNull final Email email, @NotNull final Session session, @NotNull final EmailGovernance emailGovernance) {
		try {
			final InternalEmail internalEmail = InternalEmail.requireInternalEmail(email);
			final Email effectiveEmail = internalEmail.prepareForConversion(emailGovernance);
			return InternalEmail.requireInternalEmail(effectiveEmail).renderMimeMessage(checkNonEmptyArgument(session, "session"), true);
		} catch (UnsupportedEncodingException | MessagingException e) {
			// this should never happen, so we don't acknowledge this exception (and simply bubble up)
			throw new IllegalStateException(e.getMessage(), e);
		}
	}

	/**
	 * Delegates to {@link #emlToMimeMessage(File, Session)} with a temporary Session for parsing the message.
	 */
	@NotNull
	public static MimeMessage emlToMimeMessage(@NotNull final File emlFile) {
		return emlToMimeMessage(emlFile, createDummySession());
	}

	/**
	 * Delegates to {@link #emlToMimeMessage(InputStream, Session)}.
	 */
	public static MimeMessage emlToMimeMessage(@NotNull final File emlFile, @NotNull final Session session) {
		try (final FileInputStream emlInputStream = new FileInputStream(checkNonEmptyArgument(emlFile, "emlFile"))) {
			return emlToMimeMessage(emlInputStream, session);
		} catch (final IOException e) {
			throw new EmailConverterException(format(EmailConverterException.PARSE_ERROR_EML_FROM_FILE, e.getMessage()), e);
		}
	}

	/**
	 * Parses the EML file at the supplied path with a new empty Session. The stream opened for the path is closed before this method returns.
	 *
	 * @param emlPath Path to the EML file.
	 * @return The parsed Jakarta Mail message.
	 */
	@NotNull
	public static MimeMessage emlToMimeMessage(@NotNull final Path emlPath) {
		try (InputStream emlInputStream = Files.newInputStream(checkNonEmptyArgument(emlPath, "emlPath"))) {
			return emlToMimeMessage(emlInputStream);
		} catch (IOException e) {
			throw new EmailConverterException(format(EmailConverterException.PARSE_ERROR_EML_FROM_PATH, emlPath, e.getMessage()), e);
		}
	}

	/**
	 * Delegates to {@link #emlToMimeMessage(InputStream, Session)} with a temporary Session for parsing the message.
	 */
	@NotNull
	public static MimeMessage emlToMimeMessage(@NotNull final InputStream inputStream) {
		return emlToMimeMessage(inputStream, createDummySession());
	}

	/**
	 * Relies on JavaMail's native parser of EML data, {@link MimeMessage#MimeMessage(Session, InputStream)}.
	 *
	 * @see MimeMessage#MimeMessage(Session, InputStream)
	 */
	@NotNull
	public static MimeMessage emlToMimeMessage(@NotNull final InputStream inputStream, @NotNull final Session session) {
		try {
			return new MimeMessage(session, inputStream);
		} catch (final MessagingException e) {
			throw new EmailConverterException(format(EmailConverterException.PARSE_ERROR_EML_FROM_STREAM, e.getMessage()), e);
		}
	}

	/**
	 * Delegates to {@link #emlToMimeMessage(String, Session)} with an empty {@link Session} instance.
	 */
	public static MimeMessage emlToMimeMessage(@NotNull final String eml) {
		return emlToMimeMessage(checkNonEmptyArgument(eml, "eml"), createDummySession());
	}

	/**
	 * Relies on JavaMail's native parser of EML data, {@link MimeMessage#MimeMessage(Session, InputStream)}.
	 */
	public static MimeMessage emlToMimeMessage(@NotNull final String eml, @NotNull final Session session) {
		checkNonEmptyArgument(session, "session");
		checkNonEmptyArgument(eml, "eml");
		try {
			return new MimeMessage(session, new ByteArrayInputStream(eml.getBytes(UTF_8)));
		} catch (final MessagingException e) {
			throw new EmailConverterException(format(EmailConverterException.PARSE_ERROR_EML_FROM_STREAM, e.getMessage()), e);
		}
	}

	/**
	 * @return The result of {@link MimeMessage#writeTo(OutputStream)} which should be in the standard EML format.
	 */
	public static byte[] mimeMessageToEMLByteArray(@NotNull final MimeMessage mimeMessage) {
		final ByteArrayOutputStream os = new ByteArrayOutputStream();
		try {
			checkNonEmptyArgument(mimeMessage, "mimeMessage").writeTo(os);
			return os.toByteArray();
		} catch (IOException | MessagingException e) {
			// this should never happen, so we don't acknowledge this exception (and simply bubble up)
			throw new IllegalStateException("This should never happen", e);
		}
	}

	/**
	 * @return The result of {@link MimeMessage#writeTo(OutputStream)} with which should be in the standard EML format, to UTF8 string.
	 */
	public static String mimeMessageToEML(@NotNull final MimeMessage mimeMessage) {
		final ByteArrayOutputStream os = new ByteArrayOutputStream();
		try {
			checkNonEmptyArgument(mimeMessage, "mimeMessage").writeTo(os);
			return os.toString(UTF_8.name());
		} catch (IOException | MessagingException e) {
			// this should never happen, so we don't acknowledge this exception (and simply bubble up)
			throw new IllegalStateException("This should never happen", e);
		}
	}

	/**
	 * Delegates to {@link #emailToMimeMessage(Email)} and passes the result to {@link #mimeMessageToEML(MimeMessage)}.
	 *
	 * @see #emailToMimeMessage(Email, Session)
	 */
	public static String emailToEML(@NotNull final Email email) {
		return mimeMessageToEML(emailToMimeMessage(checkNonEmptyArgument(email, "email")));
	}

	/**
	 * Converts an email to EML bytes. For exact EML these are the authoritative input bytes.
	 *
	 * @return A defensive byte array containing the message's EML representation.
	 */
	@NotNull
	public static byte[] emailToEMLByteArray(@NotNull final Email email) {
		return mimeMessageToEMLByteArray(emailToMimeMessage(checkNonEmptyArgument(email, "email")));
	}

	/**
	 * Delegates to {@link #outlookMsgToEML(String, Pkcs12Config)}.
	 *
	 * @param msgFileName The file name of an Outlook (.msg) message to convert.
	 */
	@NotNull
	public static String outlookMsgToEML(@NotNull final String msgFileName) {
		return outlookMsgToEML(msgFileName, null);
	}

	/**
	 * @param msgFileName The file name of an Outlook (.msg) message to convert.
	 * @param pkcs12Config Private key store for decrypting S/MIME encrypted attachments
	 *                     (only needed when the message is encrypted rather than just signed).
	 * @return Result of {@link #outlookMsgToEmail(String, Pkcs12Config)} and {@link #emailToEML(Email)}
	 */
	@NotNull
	public static String outlookMsgToEML(@NotNull final String msgFileName, @Nullable final Pkcs12Config pkcs12Config) {
		checkNonEmptyArgument(msgFileName, "msgFileName");
		return emailToEML(outlookMsgToEmail(msgFileName, pkcs12Config));
	}

	/**
	 * Delegates to {@link #outlookMsgToEML(File, Pkcs12Config)}.
	 */
	@NotNull
	public static String outlookMsgToEML(@NotNull final File outlookMsgFile) {
		return outlookMsgToEML(outlookMsgFile, null);
	}

	/**
	 * @return Result of {@link #outlookMsgToEmail(File, Pkcs12Config)} and {@link #emailToEML(Email)}
	 */
	@NotNull
	public static String outlookMsgToEML(@NotNull final File outlookMsgFile, @Nullable final Pkcs12Config pkcs12Config) {
		checkNonEmptyArgument(outlookMsgFile, "outlookMsgFile");
		return emailToEML(outlookMsgToEmail(outlookMsgFile, pkcs12Config));
	}

	/**
	 * Delegates to {@link #outlookMsgToEML(InputStream, Pkcs12Config)}.
	 */
	@NotNull
	public static String outlookMsgToEML(@NotNull final InputStream outlookMsgInputStream) {
		return outlookMsgToEML(outlookMsgInputStream, null);
	}

	/**
	 * @return Result of {@link #outlookMsgToEmail(InputStream, Pkcs12Config)} and {@link #emailToEML(Email)}
	 */
	@NotNull
	public static String outlookMsgToEML(@NotNull final InputStream outlookMsgInputStream, @Nullable final Pkcs12Config pkcs12Config) {
		checkNonEmptyArgument(outlookMsgInputStream, "outlookMsgInputStream");
		return emailToEML(outlookMsgToEmail(outlookMsgInputStream, pkcs12Config));
	}

	private static ConfiguredEmailConverterImpl conventionalConverter() {
		// Keep released static parsing/ownership semantics while the configured public surface remains the fluent from... API.
		return new ConfiguredEmailConverterImpl(SimpleJavaMail.fromDefaults().getConfig());
	}

	private static Session createDummySession() {
		return JakartaMailImplementation.createSession(new Properties());
	}

}
