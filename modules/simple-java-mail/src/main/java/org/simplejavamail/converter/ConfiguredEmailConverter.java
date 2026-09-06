package org.simplejavamail.converter;

import jakarta.mail.Session;
import jakarta.mail.internet.MimeMessage;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.simplejavamail.api.SimpleJavaMail;
import org.simplejavamail.api.email.EmailPopulatingBuilder;
import org.simplejavamail.api.email.EmailStartingBuilder;
import org.simplejavamail.api.email.config.OpenPgpReceiveConfig;
import org.simplejavamail.api.mailer.config.Pkcs12Config;
import org.simplejavamail.api.outlook.OutlookEmailConversionResult;
import org.simplejavamail.config.SimpleJavaMailConfig;
import org.simplejavamail.email.internal.EmailPopulatingBuilderFactoryImpl;
import org.simplejavamail.email.internal.EmailStartingBuilderImpl;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;

import static java.lang.String.format;
import static java.util.Objects.requireNonNull;

/**
 * Conversion entry point that retains one {@link SimpleJavaMailConfig} snapshot for every builder it creates, including builders for nested messages.
 * Obtain this object from {@link SimpleJavaMail#converter()} so conversion and the other builders from that factory use the same snapshot.
 *
 * @see EmailConverter
 * @see EmailPopulatingBuilder
 */
public final class ConfiguredEmailConverter {

	private final EmailStartingBuilder emailStartingBuilder;
	private final EmailPopulatingBuilderFactoryImpl emailPopulatingBuilderFactory;

	/**
	 * Creates a converter for an immutable configuration snapshot.
	 *
	 * @param config The snapshot retained by builders produced during conversion.
	 * @see SimpleJavaMail#converter()
	 */
	public ConfiguredEmailConverter(@NotNull final SimpleJavaMailConfig config) {
		final SimpleJavaMailConfig requiredConfig = requireNonNull(config, "config");
		this.emailStartingBuilder = new EmailStartingBuilderImpl(requiredConfig);
		this.emailPopulatingBuilderFactory = new EmailPopulatingBuilderFactoryImpl(requiredConfig);
	}

	/**
	 * Converts a MIME message into a builder that follows {@link EmailPopulatingBuilder}'s configuration and resolution rules.
	 */
	@NotNull
	public EmailPopulatingBuilder mimeMessageToEmailBuilder(@NotNull final MimeMessage mimeMessage) {
		return mimeMessageToEmailBuilder(mimeMessage, null, null, true);
	}

	/**
	 * Converts a MIME message into a builder that follows {@link EmailPopulatingBuilder}'s configuration and resolution rules.
	 */
	@NotNull
	public EmailPopulatingBuilder mimeMessageToEmailBuilder(@NotNull final MimeMessage mimeMessage,
			@Nullable final Pkcs12Config pkcs12Config,
			@Nullable final OpenPgpReceiveConfig openPgpReceiveConfig,
			final boolean fetchAttachmentData) {
		return EmailConverter.mimeMessageToEmailBuilder(
				mimeMessage, pkcs12Config, openPgpReceiveConfig, fetchAttachmentData, this);
	}

	/**
	 * Parses EML data into a builder that follows {@link EmailPopulatingBuilder}'s configuration and resolution rules.
	 */
	@NotNull
	public EmailPopulatingBuilder emlToEmailBuilder(@NotNull final InputStream emlInputStream) {
		return emlToEmailBuilder(emlInputStream, null, null, EmailConverter.createDummySession());
	}

	/**
	 * Parses the EML file into a builder and closes the stream opened for the supplied path before returning.
	 *
	 * @param emlPath Path to the EML file.
	 * @return An editable builder populated from the message.
	 */
	@NotNull
	public EmailPopulatingBuilder emlToEmailBuilder(@NotNull final Path emlPath) {
		return emlToEmailBuilder(emlPath, null, null, EmailConverter.createDummySession());
	}

	/**
	 * Full Path-based EML entry point for callers that also need receive-side security configuration or a specific Jakarta Mail Session. The stream opened
	 * for the path is closed before this method returns.
	 *
	 * @param emlPath Path to the EML file.
	 * @param pkcs12Config Private key configuration for S/MIME decryption, or {@code null} when not needed.
	 * @param openPgpReceiveConfig OpenPGP verification and decryption configuration, or {@code null} when not needed.
	 * @param session Jakarta Mail Session used while parsing the message.
	 * @return An editable builder populated from the message.
	 */
	@NotNull
	public EmailPopulatingBuilder emlToEmailBuilder(@NotNull final Path emlPath,
			@Nullable final Pkcs12Config pkcs12Config,
			@Nullable final OpenPgpReceiveConfig openPgpReceiveConfig,
			@NotNull final Session session) {
		try (InputStream emlInputStream = Files.newInputStream(requireNonNull(emlPath, "emlPath"))) {
			return emlToEmailBuilder(emlInputStream, pkcs12Config, openPgpReceiveConfig, session);
		} catch (IOException e) {
			throw new EmailConverterException(format(EmailConverterException.PARSE_ERROR_EML_FROM_PATH, emlPath, e.getMessage()), e);
		}
	}

	/**
	 * Parses EML data into a builder that follows {@link EmailPopulatingBuilder}'s configuration and resolution rules.
	 */
	@NotNull
	public EmailPopulatingBuilder emlToEmailBuilder(@NotNull final InputStream emlInputStream,
			@Nullable final Pkcs12Config pkcs12Config,
			@Nullable final OpenPgpReceiveConfig openPgpReceiveConfig,
			@NotNull final Session session) {
		return EmailConverter.emlToEmailBuilder(emlInputStream, pkcs12Config, openPgpReceiveConfig, session, this);
	}

	/**
	 * Converts an Outlook message and retains the Outlook-specific source data.
	 */
	@NotNull
	public OutlookEmailConversionResult outlookMsgToEmailBuilderWithOutlookData(@NotNull final String msgFileName,
			@Nullable final Pkcs12Config pkcs12Config) {
		return EmailConverter.outlookMsgToEmailBuilderWithOutlookData(msgFileName, pkcs12Config, this);
	}

	/**
	 * Converts an Outlook message and retains the Outlook-specific source data.
	 */
	@NotNull
	public OutlookEmailConversionResult outlookMsgToEmailBuilderWithOutlookData(@NotNull final File msgFile,
			@Nullable final Pkcs12Config pkcs12Config) {
		return EmailConverter.outlookMsgToEmailBuilderWithOutlookData(msgFile, pkcs12Config, this);
	}

	/**
	 * Converts an Outlook message and retains the Outlook-specific source data. The stream opened for the path is closed before this method returns.
	 *
	 * @param msgPath Path to the Outlook MSG file.
	 * @param pkcs12Config Private key configuration for S/MIME decryption, or {@code null} when not needed.
	 * @return The editable email builder together with Outlook-specific source data.
	 */
	@NotNull
	public OutlookEmailConversionResult outlookMsgToEmailBuilderWithOutlookData(@NotNull final Path msgPath,
			@Nullable final Pkcs12Config pkcs12Config) {
		try (InputStream msgInputStream = Files.newInputStream(requireNonNull(msgPath, "msgPath"))) {
			return outlookMsgToEmailBuilderWithOutlookData(msgInputStream, pkcs12Config);
		} catch (IOException e) {
			throw new EmailConverterException(format(EmailConverterException.ERROR_READING_OUTLOOK_PATH, msgPath, e.getMessage()), e);
		}
	}

	/**
	 * Converts an Outlook message and retains the Outlook-specific source data.
	 */
	@NotNull
	public OutlookEmailConversionResult outlookMsgToEmailBuilderWithOutlookData(@NotNull final InputStream msgInputStream,
			@Nullable final Pkcs12Config pkcs12Config) {
		return EmailConverter.outlookMsgToEmailBuilderWithOutlookData(msgInputStream, pkcs12Config, this);
	}

	EmailStartingBuilder getEmailStartingBuilder() {
		return emailStartingBuilder;
	}

	EmailPopulatingBuilderFactoryImpl getEmailPopulatingBuilderFactory() {
		return emailPopulatingBuilderFactory;
	}
}
