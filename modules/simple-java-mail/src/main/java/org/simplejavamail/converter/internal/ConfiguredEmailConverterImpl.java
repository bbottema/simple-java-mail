package org.simplejavamail.converter.internal;

import jakarta.mail.MessagingException;
import jakarta.mail.Session;
import jakarta.mail.internet.ContentType;
import jakarta.mail.internet.MimeMessage;
import java.io.File;
import java.io.ByteArrayInputStream;
import java.io.FilterInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Properties;
import lombok.val;
import org.jetbrains.annotations.ApiStatus;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.simplejavamail.api.SimpleJavaMail;
import org.simplejavamail.api.converter.ConfiguredEmailConverter;
import org.simplejavamail.api.email.AttachmentResource;
import org.simplejavamail.api.email.Email;
import org.simplejavamail.api.email.EmailPopulatingBuilder;
import org.simplejavamail.api.email.EmailStartingBuilder;
import org.simplejavamail.internal.email.OpenPgpDetailsImpl;
import org.simplejavamail.api.email.OriginalOpenPgpDetails;
import org.simplejavamail.api.email.OriginalOpenPgpDetails.DecryptionStatus;
import org.simplejavamail.api.email.OriginalOpenPgpDetails.OpenPgpMode;
import org.simplejavamail.api.email.OriginalOpenPgpDetails.SignatureStatus;
import org.simplejavamail.api.email.OriginalSmimeDetails;
import org.simplejavamail.api.email.OriginalSmimeDetails.SmimeMode;
import org.simplejavamail.api.email.config.OpenPgpReceiveConfig;
import org.simplejavamail.api.internal.outlooksupport.model.EmailFromOutlookMessage;
import org.simplejavamail.api.internal.outlooksupport.model.OutlookMessage;
import org.simplejavamail.api.internal.protectionsupport.model.InboundProtectionResult;
import org.simplejavamail.api.internal.smimesupport.builder.SmimeParseResult;
import org.simplejavamail.api.internal.smimesupport.model.SmimePreprocessingResult;
import org.simplejavamail.api.mailer.config.Pkcs12Config;
import org.simplejavamail.api.outlook.OutlookEmailConversionResult;
import org.simplejavamail.config.SimpleJavaMailConfig;
import org.simplejavamail.converter.internal.mimemessage.MimeMessageParser;
import org.simplejavamail.converter.EmailConverter;
import org.simplejavamail.email.internal.EmailPopulatingBuilderFactoryImpl;
import org.simplejavamail.email.internal.EmailStartingBuilderImpl;
import org.simplejavamail.email.internal.InternalEmailPopulatingBuilder;
import org.simplejavamail.internal.moduleloader.ModuleLoader;
import org.simplejavamail.internal.smimesupport.model.OriginalSmimeDetailsImpl;
import org.simplejavamail.internal.util.FinalizedMimeMessage;
import org.simplejavamail.internal.util.JakartaMailImplementation;

import static java.lang.String.format;
import static java.nio.charset.StandardCharsets.UTF_8;
import static java.util.Collections.singletonList;
import static java.util.Objects.requireNonNull;
import static org.simplejavamail.api.email.OriginalSmimeDetails.SmimeMode.PLAIN;
import static org.simplejavamail.converter.EmailConverter.mimeMessageToEMLByteArray;
import static org.simplejavamail.internal.moduleloader.ModuleLoader.loadSmimeModule;
import static org.simplejavamail.internal.util.MiscUtil.readInputStreamToBytes;
import static org.simplejavamail.internal.util.Preconditions.checkNonEmptyArgument;

/**
 * Conversion entry point that retains one {@link SimpleJavaMailConfig} snapshot for every builder it creates, including builders for nested messages.
 * Obtain this object from {@link SimpleJavaMail#converter()} so conversion and the other builders from that factory use the same snapshot.
 *
 * @see ConfiguredEmailConverter
 * @see EmailPopulatingBuilder
 */
@ApiStatus.Internal
public final class ConfiguredEmailConverterImpl implements ConfiguredEmailConverter {

	private final EmailStartingBuilder emailStartingBuilder;
	private final EmailPopulatingBuilderFactoryImpl emailPopulatingBuilderFactory;

	/**
	 * Creates a converter for an immutable configuration snapshot.
	 *
	 * @param config The snapshot retained by builders produced during conversion.
	 * @see SimpleJavaMail#converter()
	 */
	public ConfiguredEmailConverterImpl(@NotNull final SimpleJavaMailConfig config) {
		final SimpleJavaMailConfig requiredConfig = requireNonNull(config, "config");
		this.emailStartingBuilder = new EmailStartingBuilderImpl(requiredConfig);
		this.emailPopulatingBuilderFactory = new EmailPopulatingBuilderFactoryImpl(requiredConfig);
	}

	/** @see ConfiguredEmailConverter#fromEmail(Email) */
	@Override
	public EmailConversion fromEmail(@NotNull final Email email) {
		return new FluentEmailConversions.FromEmail(requireNonNull(email, "email"));
	}

	/** @see ConfiguredEmailConverter#fromMimeMessage(MimeMessage) */
	@Override
	public MimeMessageConversion fromMimeMessage(@NotNull final MimeMessage message) {
		return new FluentEmailConversions.FromMimeMessage(this, requireNonNull(message, "message"));
	}

	/** @see ConfiguredEmailConverter#fromEml(String) */
	@Override
	public EmlConversion fromEml(@NotNull final String eml) {
		return fromEml(requireNonNull(eml, "eml").getBytes(UTF_8));
	}

	/** @see ConfiguredEmailConverter#fromEml(byte[]) */
	@Override
	public EmlConversion fromEml(@NotNull final byte[] eml) {
		final byte[] captured = requireNonNull(eml, "eml").clone();
		return new FluentEmailConversions.FromEml(this,
				session -> EmailConverter.emlToMimeMessage(new ByteArrayInputStream(captured), session),
				session -> readPreservedEml(new ByteArrayInputStream(captured), session));
	}

	/** @see ConfiguredEmailConverter#fromEml(File) */
	@Override
	public EmlConversion fromEml(@NotNull final File emlFile) {
		return fromEml(requireNonNull(emlFile, "emlFile").toPath());
	}

	/** @see ConfiguredEmailConverter#fromEml(Path) */
	@Override
	public EmlConversion fromEml(@NotNull final Path emlPath) {
		final Path captured = requireNonNull(emlPath, "emlPath");
		return new FluentEmailConversions.FromEml(this,
				session -> parseEmlPath(captured, session),
				session -> readPreservedEmlPath(captured, session));
	}

	/** @see ConfiguredEmailConverter#fromEml(InputStream) */
	@Override
	public EmlConversion fromEml(@NotNull final InputStream emlInputStream) {
		final InputStream captured = requireNonNull(emlInputStream, "emlInputStream");
		return new FluentEmailConversions.FromEml(this,
				session -> EmailConverter.emlToMimeMessage(captured, session),
				session -> readPreservedEml(captured, session));
	}

	/** @see ConfiguredEmailConverter#fromOutlookMsg(String) */
	@Override
	public OutlookConversion fromOutlookMsg(@NotNull final String msgFileName) {
		final String captured = requireNonNull(msgFileName, "msgFileName");
		return new FluentEmailConversions.FromOutlook(pkcs12 -> outlookMsgToEmailBuilderWithOutlookData(captured, pkcs12));
	}

	/** @see ConfiguredEmailConverter#fromOutlookMsg(File) */
	@Override
	public OutlookConversion fromOutlookMsg(@NotNull final File msgFile) {
		final File captured = requireNonNull(msgFile, "msgFile");
		return new FluentEmailConversions.FromOutlook(pkcs12 -> outlookMsgToEmailBuilderWithOutlookData(captured, pkcs12));
	}

	/** @see ConfiguredEmailConverter#fromOutlookMsg(Path) */
	@Override
	public OutlookConversion fromOutlookMsg(@NotNull final Path msgPath) {
		final Path captured = requireNonNull(msgPath, "msgPath");
		return new FluentEmailConversions.FromOutlook(pkcs12 -> outlookMsgToEmailBuilderWithOutlookData(captured, pkcs12));
	}

	/** @see ConfiguredEmailConverter#fromOutlookMsg(InputStream) */
	@Override
	public OutlookConversion fromOutlookMsg(@NotNull final InputStream msgInputStream) {
		final InputStream captured = requireNonNull(msgInputStream, "msgInputStream");
		return new FluentEmailConversions.FromOutlook(pkcs12 -> outlookMsgToEmailBuilderWithOutlookData(leavingOpen(captured), pkcs12));
	}

	private static InputStream leavingOpen(final InputStream input) {
		// The Outlook parser closes its input. A fluent conversion borrows the caller's stream rather than transferring its ownership.
		return new FilterInputStream(input) {
			@Override
			public void close() {
				// The application that supplied the stream remains responsible for closing it.
			}
		};
	}

	private static MimeMessage parseEmlPath(final Path path, final Session session) {
		try (InputStream input = Files.newInputStream(path)) {
			return EmailConverter.emlToMimeMessage(input, session);
		} catch (IOException e) {
			throw new EmailConverterException(format(EmailConverterException.PARSE_ERROR_EML_FROM_PATH, path, e.getMessage()), e);
		}
	}

	private static MimeMessage readPreservedEmlPath(final Path path, final Session session) {
		try (InputStream input = Files.newInputStream(path)) {
			return readPreservedEml(input, session);
		} catch (IOException e) {
			throw new EmailConverterException(format(EmailConverterException.PARSE_ERROR_EML_FROM_PATH, path, e.getMessage()), e);
		}
	}

	private static MimeMessage readPreservedEml(final InputStream input, final Session session) {
		try {
			// Protection inspection must retain the received representation, including line endings tolerated by the MIME parser.
			final byte[] emlBytes = readInputStreamToBytes(checkNonEmptyArgument(input, "emlInputStream"));
			return FinalizedMimeMessage.fromMessageBytes(
					checkNonEmptyArgument(session, "session"), emlBytes, FinalizedMimeMessage.ProtectionState.NONE);
		} catch (IOException | MessagingException e) {
			throw new EmailConverterException(EmailConverterException.ERROR_READING_EML_INPUTSTREAM, e);
		}
	}

	/** Internal import shared by the fluent source stages and the released static conversion API. */
	@NotNull
	public EmailPopulatingBuilder mimeMessageToEmailBuilder(@NotNull final MimeMessage mimeMessage,
			@Nullable final Pkcs12Config pkcs12Config,
			@Nullable final OpenPgpReceiveConfig openPgpReceiveConfig,
			final boolean fetchAttachmentData) {
		checkNonEmptyArgument(mimeMessage, "mimeMessage");
		requireSmimeForExplicitDecryption(pkcs12Config);
		final InboundProtectionResult<OriginalOpenPgpDetails> openPgpResult = preprocessOpenPgp(mimeMessage, openPgpReceiveConfig);
		final SmimePreprocessingResult smimeResult = openPgpResult.isRecognized()
				? null
				: preprocessSmime(openPgpResult.getEffectiveMimeMessage(), pkcs12Config);
		final MimeMessage effectiveMessage = smimeResult != null && smimeResult.isRecognized()
				? smimeResult.getEffectiveMimeMessage()
				: openPgpResult.getEffectiveMimeMessage();
		val builder = emailStartingBuilder.startingBlank();
		val parsed = MimeMessageParser.parseMimeMessage(effectiveMessage, fetchAttachmentData);
		val emailBuilder = MimeMessageEmailMapper.populate(builder, parsed);
		((InternalEmailPopulatingBuilder) emailBuilder).withOriginalOpenPgpDetails(openPgpResult.getDetails());
		if (openPgpResult.isRecognized()) {
			return emailBuilder;
		}
		if (smimeResult != null && smimeResult.isRecognized()) {
			return applySmimePreprocessingResult(emailBuilder, smimeResult);
		}
		return decryptAttachments(emailBuilder, effectiveMessage, pkcs12Config);
	}

	/** Internal EML import retaining the static API's existing input handling. */
	@NotNull
	private EmailPopulatingBuilder emlToEmailBuilder(@NotNull final InputStream emlInputStream) {
		return emlToEmailBuilder(emlInputStream, null, null, createDummySession());
	}

	/** Internal Path import retaining the static API's existing input handling. */
	@NotNull
	public EmailPopulatingBuilder emlToEmailBuilder(@NotNull final Path emlPath) {
		return emlToEmailBuilder(emlPath, null, null, createDummySession());
	}

	/** Reads a Path for the compatibility entry points and closes only the stream opened here. */
	@NotNull
	private EmailPopulatingBuilder emlToEmailBuilder(@NotNull final Path emlPath,
			@Nullable final Pkcs12Config pkcs12Config,
			@Nullable final OpenPgpReceiveConfig openPgpReceiveConfig,
			@NotNull final Session session) {
		try (InputStream emlInputStream = Files.newInputStream(requireNonNull(emlPath, "emlPath"))) {
			return emlToEmailBuilder(emlInputStream, pkcs12Config, openPgpReceiveConfig, session);
		} catch (IOException e) {
			throw new EmailConverterException(format(EmailConverterException.PARSE_ERROR_EML_FROM_PATH, emlPath, e.getMessage()), e);
		}
	}

	/** Reads caller-owned EML into the existing byte-preserving parsing path. */
	@NotNull
	public EmailPopulatingBuilder emlToEmailBuilder(@NotNull final InputStream emlInputStream,
			@Nullable final Pkcs12Config pkcs12Config,
			@Nullable final OpenPgpReceiveConfig openPgpReceiveConfig,
			@NotNull final Session session) {
		return mimeMessageToEmailBuilder(readPreservedEml(emlInputStream, session),
				pkcs12Config, openPgpReceiveConfig, EmailStartingBuilder.DEFAULT_FETCH_ATTACHMENT_DATA);
	}

	/** Internal Outlook import shared by fluent conversion and the released static entry points. */
	@NotNull
	public OutlookEmailConversionResult outlookMsgToEmailBuilderWithOutlookData(@NotNull final String msgFileName,
			@Nullable final Pkcs12Config pkcs12Config) {
		checkNonEmptyArgument(msgFileName, "msgFile");
		requireSmimeForExplicitDecryption(pkcs12Config);
		EmailFromOutlookMessage result = ModuleLoader.loadOutlookModule()
				.outlookMsgToEmailBuilder(msgFileName, emailStartingBuilder, emailPopulatingBuilderFactory, InternalEmailConverterImpl.INSTANCE);
		return toOutlookEmailConversionResult(result, pkcs12Config);
	}

	/** Internal Outlook import shared by fluent conversion and the released static entry points. */
	@NotNull
	public OutlookEmailConversionResult outlookMsgToEmailBuilderWithOutlookData(@NotNull final File msgFile,
			@Nullable final Pkcs12Config pkcs12Config) {
		checkNonEmptyArgument(msgFile, "msgFile");
		requireSmimeForExplicitDecryption(pkcs12Config);
		EmailFromOutlookMessage result = ModuleLoader.loadOutlookModule()
				.outlookMsgToEmailBuilder(msgFile, emailStartingBuilder, emailPopulatingBuilderFactory, InternalEmailConverterImpl.INSTANCE);
		return toOutlookEmailConversionResult(result, pkcs12Config);
	}

	/** Internal Outlook Path import; closes the stream opened here. */
	@NotNull
	public OutlookEmailConversionResult outlookMsgToEmailBuilderWithOutlookData(@NotNull final Path msgPath,
			@Nullable final Pkcs12Config pkcs12Config) {
		try (InputStream msgInputStream = Files.newInputStream(requireNonNull(msgPath, "msgPath"))) {
			return outlookMsgToEmailBuilderWithOutlookData(msgInputStream, pkcs12Config);
		} catch (IOException e) {
			throw new EmailConverterException(format(EmailConverterException.ERROR_READING_OUTLOOK_PATH, msgPath, e.getMessage()), e);
		}
	}

	/** Internal Outlook stream import. The fluent facade shields caller streams from the parser's established close behavior. */
	@NotNull
	public OutlookEmailConversionResult outlookMsgToEmailBuilderWithOutlookData(@NotNull final InputStream msgInputStream,
			@Nullable final Pkcs12Config pkcs12Config) {
		return loadOutlookMessage(msgInputStream, pkcs12Config).toOutlookEmailConversionResult();
	}

	@Nullable
	private static SmimePreprocessingResult preprocessSmime(@NotNull final MimeMessage mimeMessage,
			@Nullable final Pkcs12Config pkcs12Config) {
		if (!ModuleLoader.smimeModuleAvailable()) {
			return null;
		}
		final Session session = mimeMessage.getSession() != null ? mimeMessage.getSession() : createDummySession();
		return ModuleLoader.loadSmimeModule().processIncoming(session, mimeMessage, pkcs12Config);
	}

	@NotNull
	private EmailPopulatingBuilder applySmimePreprocessingResult(
			@NotNull final EmailPopulatingBuilder emailBuilder,
			@NotNull final SmimePreprocessingResult result) {
		final List<AttachmentResource> clearAttachments = new ArrayList<>(emailBuilder.getAttachments());
		AttachmentResource effectiveMessageArtifact = null;
		for (AttachmentResource artifact : result.getDecryptedArtifacts()) {
			if ("message/rfc822".equalsIgnoreCase(artifact.getDataSource().getContentType())) {
				effectiveMessageArtifact = artifact;
				break;
			}
		}
		if (effectiveMessageArtifact != null) {
			emailBuilder.clearAttachments();
			emailBuilder.clearEmbeddedImages();
			((InternalEmailPopulatingBuilder) emailBuilder).clearContentTransferEncoding();
		}
		for (AttachmentResource protectedAttachment : result.getProtectedAttachments()) {
			if (!hasAttachmentNamed(emailBuilder.getAttachments(), protectedAttachment.getName())) {
				emailBuilder.withAttachments(singletonList(protectedAttachment));
			}
		}
		final List<AttachmentResource> decrypted = new ArrayList<>(result.getDecryptedArtifacts());
		if (effectiveMessageArtifact == null) {
			decrypted.addAll(clearAttachments);
		}
		final InternalEmailPopulatingBuilder internal = (InternalEmailPopulatingBuilder) emailBuilder;
		internal.withDecryptedAttachments(decrypted);
		internal.withOriginalSmimeDetails(result.getDetails());
		if (effectiveMessageArtifact != null) {
			// Nested protected messages must use this converter's snapshot too, not the static converter's default factory.
			final EmailPopulatingBuilder nestedBuilder = emlAttachmentToEmailBuilder(effectiveMessageArtifact);
			if (result.getNestedSignedDetails() != null) {
				((InternalEmailPopulatingBuilder) nestedBuilder).withOriginalSmimeDetails(result.getNestedSignedDetails());
			}
			internal.withSmimeSignedEmail(nestedBuilder.buildEmail());
		}
		return emailBuilder;
	}

	@NotNull
	private EmailPopulatingBuilder emlAttachmentToEmailBuilder(@NotNull final AttachmentResource attachment) {
		try (InputStream input = attachment.openStream()) {
			return emlToEmailBuilder(input);
		} catch (IOException e) {
			throw new EmailConverterException(EmailConverterException.ERROR_READING_EML_INPUTSTREAM, e);
		}
	}

	private static boolean hasAttachmentNamed(
			@NotNull final List<AttachmentResource> attachments,
			@Nullable final String name) {
		for (AttachmentResource attachment : attachments) {
			if (Objects.equals(attachment.getName(), name)) {
				return true;
			}
		}
		return false;
	}

	@NotNull
	private static InboundProtectionResult<OriginalOpenPgpDetails> preprocessOpenPgp(@NotNull final MimeMessage mimeMessage,
			@Nullable final OpenPgpReceiveConfig openPgpReceiveConfig) {
		try {
			final Session session = mimeMessage.getSession() != null ? mimeMessage.getSession() : createDummySession();
			final boolean protectedWithOpenPgp = isOpenPgpMime(mimeMessage);
			if (ModuleLoader.openPgpModuleAvailable()) {
				return ModuleLoader.loadOpenPgpModule().processIncoming(
						session, mimeMessage, openPgpReceiveConfig);
			}
			if (openPgpReceiveConfig != null) {
				return ModuleLoader.loadOpenPgpModule().processIncoming(
						session, mimeMessage, openPgpReceiveConfig);
			}
			if (protectedWithOpenPgp) {
				final boolean signed = mimeMessage.isMimeType("multipart/signed");
				return UnprocessedOpenPgpMail.builder()
						.recognized(true)
						.effectiveMimeMessage(mimeMessage)
					.details(OpenPgpDetailsImpl.builder()
								.openPgpMode(signed ? OpenPgpMode.SIGNED : OpenPgpMode.ENCRYPTED)
								.signatureStatus(signed ? SignatureStatus.ERROR : SignatureStatus.NOT_PRESENT)
								.decryptionStatus(signed ? DecryptionStatus.NOT_ENCRYPTED : DecryptionStatus.FAILED)
								.failureReason("OpenPGP/MIME message was preserved because openpgp-module is not available")
								.originalProtectedMessage(mimeMessageToEMLByteArray(mimeMessage))
								.build())
						.build();
			}
			return UnprocessedOpenPgpMail.builder()
					.recognized(false)
					.effectiveMimeMessage(mimeMessage)
				.details(OpenPgpDetailsImpl.plain())
					.build();
		} catch (MessagingException e) {
			return UnprocessedOpenPgpMail.builder()
					.recognized(false)
					.effectiveMimeMessage(mimeMessage)
				.details(OpenPgpDetailsImpl.plain())
					.build();
		}
	}

	private static boolean isOpenPgpMime(final MimeMessage mimeMessage) throws MessagingException {
		if (!mimeMessage.isMimeType("multipart/signed") && !mimeMessage.isMimeType("multipart/encrypted")) {
			return false;
		}
		final String protocol = new ContentType(mimeMessage.getContentType()).getParameter("protocol");
		return "application/pgp-signature".equalsIgnoreCase(protocol)
				|| "application/pgp-encrypted".equalsIgnoreCase(protocol);
	}

	@NotNull
	private EmailFromOutlookMessage loadOutlookMessage(@NotNull final InputStream msgInputStream,
			@Nullable final Pkcs12Config pkcs12Config) {
		requireSmimeForExplicitDecryption(pkcs12Config);
		EmailFromOutlookMessage fromMsgBuilder = ModuleLoader.loadOutlookModule()
				.outlookMsgToEmailBuilder(msgInputStream, emailStartingBuilder, emailPopulatingBuilderFactory, InternalEmailConverterImpl.INSTANCE);
		decryptAttachments(fromMsgBuilder.getEmailBuilder(), fromMsgBuilder.getOutlookMessage(), pkcs12Config);
		return fromMsgBuilder;
	}

	private static void requireSmimeForExplicitDecryption(@Nullable final Pkcs12Config pkcs12Config) {
		if (pkcs12Config != null) {
			// Explicit decryption is a requested feature, not the optional inspection attempted when its module happens to be present.
			loadSmimeModule();
		}
	}

	@NotNull
	private OutlookEmailConversionResult toOutlookEmailConversionResult(@NotNull final EmailFromOutlookMessage result,
			@Nullable final Pkcs12Config pkcs12Config) {
		decryptAttachments(result.getEmailBuilder(), result.getOutlookMessage(), pkcs12Config);
		return result.toOutlookEmailConversionResult();
	}

	private EmailPopulatingBuilder decryptAttachments(final EmailPopulatingBuilder emailBuilder,
			final OutlookMessage outlookMessage,
			@Nullable final Pkcs12Config pkcs12Config) {
		if (ModuleLoader.smimeModuleAvailable()) {
			SmimeParseResult smimeParseResult = loadSmimeModule().decryptAttachments(emailBuilder.getAttachments(), outlookMessage, pkcs12Config);
			handleSmimeParseResult((InternalEmailPopulatingBuilder) emailBuilder, smimeParseResult);
			updateEmailIfBothSignedAndEncrypted(emailBuilder, smimeParseResult);
		} else {
			((InternalEmailPopulatingBuilder) emailBuilder).withOriginalSmimeDetails(UnavailableSmimeInspection.fromOutlook(outlookMessage));
		}
		return emailBuilder;
	}

	@NotNull
	private EmailPopulatingBuilder decryptAttachments(final EmailPopulatingBuilder emailBuilder,
			final MimeMessage mimeMessage,
			@Nullable final Pkcs12Config pkcs12Config) {
		if (ModuleLoader.smimeModuleAvailable()) {
			SmimeParseResult smimeParseResult = loadSmimeModule().decryptAttachments(emailBuilder.getAttachments(), mimeMessage, pkcs12Config);
			handleSmimeParseResult((InternalEmailPopulatingBuilder) emailBuilder, smimeParseResult);
			updateEmailIfBothSignedAndEncrypted(emailBuilder, smimeParseResult);
		} else {
			((InternalEmailPopulatingBuilder) emailBuilder).withOriginalSmimeDetails(UnavailableSmimeInspection.fromMessage(mimeMessage));
		}
		return emailBuilder;
	}

	private static void updateEmailIfBothSignedAndEncrypted(final EmailPopulatingBuilder emailBuilder, final SmimeParseResult smimeParseResult) {
		if (emailBuilder.getSmimeSignedEmail() != null) {
			OriginalSmimeDetails nestedSmime = emailBuilder.getSmimeSignedEmail().getOriginalSmimeDetails();
			OriginalSmimeDetailsImpl originalSmimeDetails = (OriginalSmimeDetailsImpl) emailBuilder.getOriginalSmimeDetails();
			if (nestedSmime.getSmimeMode() != PLAIN && nestedSmime.getSmimeMode() != originalSmimeDetails.getSmimeMode()) {
				originalSmimeDetails.completeWithSmimeMode(SmimeMode.SIGNED_ENCRYPTED);
			} else if (smimeParseResult.getDecryptedAttachmentResults().size() == 1) {
				final SmimeMode smimeMode = smimeParseResult.getDecryptedAttachmentResults().get(0).getSmimeMode();
				originalSmimeDetails.completeWithSmimeMode(smimeMode);
			}
		}
	}

	private void handleSmimeParseResult(final InternalEmailPopulatingBuilder emailBuilder,
			final SmimeParseResult smimeParseResult) {
		emailBuilder.withDecryptedAttachments(smimeParseResult.getDecryptedAttachments());
		emailBuilder.withOriginalSmimeDetails(smimeParseResult.getOriginalSmimeDetails());
		if (smimeParseResult.getSmimeSignedOrEncryptedEmail() != null) {
			emailBuilder.withSmimeSignedEmail(emlAttachmentToEmailBuilder(
					smimeParseResult.getSmimeSignedOrEncryptedEmail()).buildEmail());
		}
	}

	private static Session createDummySession() {
		return JakartaMailImplementation.createSession(new Properties());
	}

}
