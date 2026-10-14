package org.simplejavamail.api.converter;

import jakarta.mail.Session;
import jakarta.mail.internet.MimeMessage;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.simplejavamail.api.email.EmailPopulatingBuilder;
import org.simplejavamail.api.email.config.OpenPgpReceiveConfig;
import org.simplejavamail.api.mailer.config.Pkcs12Config;
import org.simplejavamail.api.outlook.OutlookEmailConversionResult;
import org.simplejavamail.config.SimpleJavaMailConfig;

import java.io.File;
import java.io.InputStream;
import java.nio.file.Path;

/**
 * Conversion entry point that retains one {@link SimpleJavaMailConfig} snapshot for every builder it creates, including builders for nested messages.
 * Obtain this object from the configured SimpleJavaMail factory so conversion and the other builders from that factory use the same snapshot.
 *
 * @see EmailPopulatingBuilder
 */
public interface ConfiguredEmailConverter {

	/**
	 * Converts a MIME message into a builder that follows {@link EmailPopulatingBuilder}'s configuration and resolution rules.
	 */
	@NotNull
	EmailPopulatingBuilder mimeMessageToEmailBuilder(@NotNull final MimeMessage mimeMessage);

	/**
	 * Converts a MIME message into a builder that follows {@link EmailPopulatingBuilder}'s configuration and resolution rules.
	 */
	@NotNull
	EmailPopulatingBuilder mimeMessageToEmailBuilder(@NotNull final MimeMessage mimeMessage,
			@Nullable final Pkcs12Config pkcs12Config,
			@Nullable final OpenPgpReceiveConfig openPgpReceiveConfig,
			final boolean fetchAttachmentData);

	/**
	 * Parses EML data into a builder that follows {@link EmailPopulatingBuilder}'s configuration and resolution rules.
	 */
	@NotNull
	EmailPopulatingBuilder emlToEmailBuilder(@NotNull final InputStream emlInputStream);

	/**
	 * Parses the EML file into a builder and closes the stream opened for the supplied path before returning.
	 *
	 * @param emlPath Path to the EML file.
	 * @return An editable builder populated from the message.
	 */
	@NotNull
	EmailPopulatingBuilder emlToEmailBuilder(@NotNull final Path emlPath);

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
	EmailPopulatingBuilder emlToEmailBuilder(@NotNull final Path emlPath,
			@Nullable final Pkcs12Config pkcs12Config,
			@Nullable final OpenPgpReceiveConfig openPgpReceiveConfig,
			@NotNull final Session session);

	/**
	 * Parses EML data into a builder that follows {@link EmailPopulatingBuilder}'s configuration and resolution rules.
	 */
	@NotNull
	EmailPopulatingBuilder emlToEmailBuilder(@NotNull final InputStream emlInputStream,
			@Nullable final Pkcs12Config pkcs12Config,
			@Nullable final OpenPgpReceiveConfig openPgpReceiveConfig,
			@NotNull final Session session);

	/**
	 * Converts an Outlook message and retains the Outlook-specific source data.
	 */
	@NotNull
	OutlookEmailConversionResult outlookMsgToEmailBuilderWithOutlookData(@NotNull final String msgFileName,
			@Nullable final Pkcs12Config pkcs12Config);

	/**
	 * Converts an Outlook message and retains the Outlook-specific source data.
	 */
	@NotNull
	OutlookEmailConversionResult outlookMsgToEmailBuilderWithOutlookData(@NotNull final File msgFile,
			@Nullable final Pkcs12Config pkcs12Config);

	/**
	 * Converts an Outlook message and retains the Outlook-specific source data. The stream opened for the path is closed before this method returns.
	 *
	 * @param msgPath Path to the Outlook MSG file.
	 * @param pkcs12Config Private key configuration for S/MIME decryption, or {@code null} when not needed.
	 * @return The editable email builder together with Outlook-specific source data.
	 */
	@NotNull
	OutlookEmailConversionResult outlookMsgToEmailBuilderWithOutlookData(@NotNull final Path msgPath,
			@Nullable final Pkcs12Config pkcs12Config);

	/**
	 * Converts an Outlook message and retains the Outlook-specific source data.
	 */
	@NotNull
	OutlookEmailConversionResult outlookMsgToEmailBuilderWithOutlookData(@NotNull final InputStream msgInputStream,
			@Nullable final Pkcs12Config pkcs12Config);
}
