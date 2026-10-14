package org.simplejavamail.converter.internal;

import jakarta.mail.MessagingException;
import jakarta.mail.Session;
import jakarta.mail.internet.ContentType;
import jakarta.mail.internet.MimeMessage;
import java.io.File;
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
import org.simplejavamail.api.email.EmailPopulatingBuilder;
import org.simplejavamail.api.email.EmailStartingBuilder;
import org.simplejavamail.api.email.OpenPgpDetails;
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
import org.simplejavamail.email.internal.EmailPopulatingBuilderFactoryImpl;
import org.simplejavamail.email.internal.EmailStartingBuilderImpl;
import org.simplejavamail.email.internal.InternalEmailPopulatingBuilder;
import org.simplejavamail.internal.moduleloader.ModuleLoader;
import org.simplejavamail.internal.smimesupport.model.OriginalSmimeDetailsImpl;
import org.simplejavamail.internal.util.FinalizedMimeMessage;
import org.simplejavamail.internal.util.JakartaMailImplementation;

import static java.lang.String.format;
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

	/** @see ConfiguredEmailConverter#mimeMessageToEmailBuilder(MimeMessage) */
	@Override
	@NotNull
	public EmailPopulatingBuilder mimeMessageToEmailBuilder(@NotNull final MimeMessage mimeMessage) {
		return mimeMessageToEmailBuilder(mimeMessage, null, null, true);
	}

	/** @see ConfiguredEmailConverter#mimeMessageToEmailBuilder(MimeMessage, Pkcs12Config, OpenPgpReceiveConfig, boolean) */
	@Override
	@NotNull
	public EmailPopulatingBuilder mimeMessageToEmailBuilder(@NotNull final MimeMessage mimeMessage,
			@Nullable final Pkcs12Config pkcs12Config,
			@Nullable final OpenPgpReceiveConfig openPgpReceiveConfig,
			final boolean fetchAttachmentData) {
		checkNonEmptyArgument(mimeMessage, "mimeMessage");
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

	/** @see ConfiguredEmailConverter#emlToEmailBuilder(InputStream) */
	@Override
	@NotNull
	public EmailPopulatingBuilder emlToEmailBuilder(@NotNull final InputStream emlInputStream) {
		return emlToEmailBuilder(emlInputStream, null, null, createDummySession());
	}

	/** @see ConfiguredEmailConverter#emlToEmailBuilder(Path) */
	@Override
	@NotNull
	public EmailPopulatingBuilder emlToEmailBuilder(@NotNull final Path emlPath) {
		return emlToEmailBuilder(emlPath, null, null, createDummySession());
	}

	/** @see ConfiguredEmailConverter#emlToEmailBuilder(Path, Pkcs12Config, OpenPgpReceiveConfig, Session) */
	@Override
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

	/** @see ConfiguredEmailConverter#emlToEmailBuilder(InputStream, Pkcs12Config, OpenPgpReceiveConfig, Session) */
	@Override
	@NotNull
	public EmailPopulatingBuilder emlToEmailBuilder(@NotNull final InputStream emlInputStream,
			@Nullable final Pkcs12Config pkcs12Config,
			@Nullable final OpenPgpReceiveConfig openPgpReceiveConfig,
			@NotNull final Session session) {
		try {
			final byte[] emlBytes = readInputStreamToBytes(checkNonEmptyArgument(emlInputStream, "emlInputStream"));
			final MimeMessage mimeMessage = FinalizedMimeMessage.fromMessageBytes(
					checkNonEmptyArgument(session, "session"), emlBytes, FinalizedMimeMessage.ProtectionState.NONE);
			return mimeMessageToEmailBuilder(mimeMessage, pkcs12Config, openPgpReceiveConfig, true);
		} catch (IOException | MessagingException e) {
			throw new EmailConverterException(EmailConverterException.ERROR_READING_EML_INPUTSTREAM, e);
		}
	}

	/** @see ConfiguredEmailConverter#outlookMsgToEmailBuilderWithOutlookData(String, Pkcs12Config) */
	@Override
	@NotNull
	public OutlookEmailConversionResult outlookMsgToEmailBuilderWithOutlookData(@NotNull final String msgFileName,
			@Nullable final Pkcs12Config pkcs12Config) {
		checkNonEmptyArgument(msgFileName, "msgFile");
		EmailFromOutlookMessage result = ModuleLoader.loadOutlookModule()
				.outlookMsgToEmailBuilder(msgFileName, emailStartingBuilder, emailPopulatingBuilderFactory, InternalEmailConverterImpl.INSTANCE);
		return toOutlookEmailConversionResult(result, pkcs12Config);
	}

	/** @see ConfiguredEmailConverter#outlookMsgToEmailBuilderWithOutlookData(File, Pkcs12Config) */
	@Override
	@NotNull
	public OutlookEmailConversionResult outlookMsgToEmailBuilderWithOutlookData(@NotNull final File msgFile,
			@Nullable final Pkcs12Config pkcs12Config) {
		checkNonEmptyArgument(msgFile, "msgFile");
		EmailFromOutlookMessage result = ModuleLoader.loadOutlookModule()
				.outlookMsgToEmailBuilder(msgFile, emailStartingBuilder, emailPopulatingBuilderFactory, InternalEmailConverterImpl.INSTANCE);
		return toOutlookEmailConversionResult(result, pkcs12Config);
	}

	/** @see ConfiguredEmailConverter#outlookMsgToEmailBuilderWithOutlookData(Path, Pkcs12Config) */
	@Override
	@NotNull
	public OutlookEmailConversionResult outlookMsgToEmailBuilderWithOutlookData(@NotNull final Path msgPath,
			@Nullable final Pkcs12Config pkcs12Config) {
		try (InputStream msgInputStream = Files.newInputStream(requireNonNull(msgPath, "msgPath"))) {
			return outlookMsgToEmailBuilderWithOutlookData(msgInputStream, pkcs12Config);
		} catch (IOException e) {
			throw new EmailConverterException(format(EmailConverterException.ERROR_READING_OUTLOOK_PATH, msgPath, e.getMessage()), e);
		}
	}

	/** @see ConfiguredEmailConverter#outlookMsgToEmailBuilderWithOutlookData(InputStream, Pkcs12Config) */
	@Override
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
			emailBuilder.clearContentTransferEncoding();
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
			final EmailPopulatingBuilder nestedBuilder = emlToEmailBuilder(effectiveMessageArtifact.getDataSourceInputStream());
			if (result.getNestedSignedDetails() != null) {
				((InternalEmailPopulatingBuilder) nestedBuilder).withOriginalSmimeDetails(result.getNestedSignedDetails());
			}
			internal.withSmimeSignedEmail(nestedBuilder.buildEmail());
		}
		return emailBuilder;
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
						.details(OpenPgpDetails.builder()
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
					.details(OpenPgpDetails.plain())
					.build();
		} catch (MessagingException e) {
			return UnprocessedOpenPgpMail.builder()
					.recognized(false)
					.effectiveMimeMessage(mimeMessage)
					.details(OpenPgpDetails.plain())
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
		EmailFromOutlookMessage fromMsgBuilder = ModuleLoader.loadOutlookModule()
				.outlookMsgToEmailBuilder(msgInputStream, emailStartingBuilder, emailPopulatingBuilderFactory, InternalEmailConverterImpl.INSTANCE);
		decryptAttachments(fromMsgBuilder.getEmailBuilder(), fromMsgBuilder.getOutlookMessage(), pkcs12Config);
		return fromMsgBuilder;
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
			emailBuilder.withSmimeSignedEmail(emlToEmailBuilder(
					smimeParseResult.getSmimeSignedOrEncryptedEmail().getDataSourceInputStream()).buildEmail());
		}
	}

	private static Session createDummySession() {
		return JakartaMailImplementation.createSession(new Properties());
	}

}
