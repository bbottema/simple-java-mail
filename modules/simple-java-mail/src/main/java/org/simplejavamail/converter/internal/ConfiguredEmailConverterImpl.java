package org.simplejavamail.converter.internal;

import org.simplejavamail.converter.internal.mimemessage.InputInspection;
import org.simplejavamail.api.outlook.OutlookMessageData;

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
import java.util.Map;
import java.util.LinkedHashMap;
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
import org.simplejavamail.api.email.EmailProblem;
import org.simplejavamail.api.email.EmailSourceData;
import org.simplejavamail.api.email.EmailSourcePart;
import org.simplejavamail.api.internal.smimesupport.model.AttachmentDecryptionResult;
import jakarta.activation.DataSource;
import java.util.function.Function;
import org.simplejavamail.api.email.config.EmailTemplateOptions;
import org.simplejavamail.api.internal.outlooksupport.model.OutlookInputException;
import org.simplejavamail.internal.email.EmailInspection;
import org.simplejavamail.internal.email.InspectionOutlookData;
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
		return new FluentEmailConversions.FromEmail(this, requireNonNull(email, "email"));
	}

	/** Creates an editable output snapshot without consulting another factory or changing the input. */
	EmailPopulatingBuilder copyForOutput(final Email email) {
		return new EmailStartingBuilderImpl(org.simplejavamail.config.ConfigLoader.builder().load()).copying(email);
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
				session -> parseEmlInput(new ByteArrayInputStream(captured), session),
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
				session -> parseEmlInput(captured, session),
				session -> readPreservedEml(captured, session));
	}

	/** @see ConfiguredEmailConverter#fromOutlookMsg(String) */
	@Override
	public OutlookConversion fromOutlookMsg(@NotNull final String msgFileName) {
		final String captured = requireNonNull(msgFileName, "msgFileName");
		return new FluentEmailConversions.FromOutlook(this, pkcs12 -> outlookMsgToEmailBuilderWithOutlookData(captured, pkcs12));
	}

	/** @see ConfiguredEmailConverter#fromOutlookMsg(File) */
	@Override
	public OutlookConversion fromOutlookMsg(@NotNull final File msgFile) {
		final File captured = requireNonNull(msgFile, "msgFile");
		return new FluentEmailConversions.FromOutlook(this, pkcs12 -> outlookMsgToEmailBuilderWithOutlookData(captured, pkcs12));
	}

	/** @see ConfiguredEmailConverter#fromOutlookMsg(Path) */
	@Override
	public OutlookConversion fromOutlookMsg(@NotNull final Path msgPath) {
		final Path captured = requireNonNull(msgPath, "msgPath");
		return new FluentEmailConversions.FromOutlook(this, pkcs12 -> outlookMsgToEmailBuilderWithOutlookData(captured, pkcs12));
	}

	/** @see ConfiguredEmailConverter#fromOutlookMsg(InputStream) */
	@Override
	public OutlookConversion fromOutlookMsg(@NotNull final InputStream msgInputStream) {
		final InputStream captured = requireNonNull(msgInputStream, "msgInputStream");
		return new FluentEmailConversions.FromOutlook(this, pkcs12 -> outlookMsgToEmailBuilderWithOutlookData(leavingOpen(captured), pkcs12));
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
		MimeMessage parsed = null;
		try (InputStream input = Files.newInputStream(path)) {
			parsed = parseEmlInput(input, session);
		} catch (IOException e) {
			if (parsed != null) throw new SourceCloseException(parsed, e);
			throw new SourceReadException(EmailSourceData.Format.MIME, null, false, EmailProblem.Code.CONTENT_READ_FAILED, e);
		}
		return parsed;
	}

	private static MimeMessage readPreservedEmlPath(final Path path, final Session session) {
		MimeMessage parsed = null;
		try (InputStream input = Files.newInputStream(path)) {
			parsed = readPreservedEml(input, session);
		} catch (IOException e) {
			if (parsed != null) throw new SourceCloseException(parsed, e);
			throw new SourceReadException(EmailSourceData.Format.MIME, null, false, EmailProblem.Code.CONTENT_READ_FAILED, e);
		}
		return parsed;
	}

	private static MimeMessage readPreservedEml(final InputStream input, final Session session) {
		final byte[] emlBytes = SourceReadException.acquire(checkNonEmptyArgument(input, "emlInputStream"), EmailSourceData.Format.MIME);
		try {
			// Protection inspection must retain the received representation, including line endings tolerated by the MIME parser.
			return FinalizedMimeMessage.fromMessageBytes(
					checkNonEmptyArgument(session, "session"), emlBytes, FinalizedMimeMessage.ProtectionState.NONE);
		} catch (MessagingException e) {
			throw new SourceReadException(EmailSourceData.Format.MIME, emlBytes, true, EmailProblem.Code.FIELD_NOT_INTERPRETED, e);
		}
	}

	private static MimeMessage parseEmlInput(final InputStream input, final Session session) {
		final byte[] bytes = SourceReadException.acquire(checkNonEmptyArgument(input, "emlInputStream"), EmailSourceData.Format.MIME);
		try {
			return new MimeMessage(checkNonEmptyArgument(session, "session"), new ByteArrayInputStream(bytes));
		} catch (MessagingException parseFailure) {
			throw new SourceReadException(EmailSourceData.Format.MIME, bytes, true, EmailProblem.Code.FIELD_NOT_INTERPRETED, parseFailure);
		}
	}

	/** Builds the received diagnostic value even when no ordinary content could be acquired. */
	EmailPopulatingBuilder sourceFailureBuilder(final SourceReadException failure) {
		org.simplejavamail.internal.util.InterruptedIoGuard.rethrowIfInterrupted(failure);
		final EmailPopulatingBuilder builder = emailStartingBuilder.startingBlank();
		if (failure.getSourceData().getFormat() == EmailSourceData.Format.OUTLOOK_MSG) {
			builder.withTemplateOptions(EmailTemplateOptions.builder().ignoringDefaults().build());
		}
		((InternalEmailPopulatingBuilder) builder).retainInspection(failure.getSourceData(), singletonList(failure.getProblem()));
		noteSuppressedInputCloseFailures(builder, failure);
		return builder;
	}

	/** Internal import shared by the fluent source stages and the released static conversion API. */
	@NotNull
	public EmailPopulatingBuilder mimeMessageToEmailBuilder(@NotNull final MimeMessage mimeMessage,
			@Nullable final Pkcs12Config pkcs12Config,
			@Nullable final OpenPgpReceiveConfig openPgpReceiveConfig,
			final boolean fetchAttachmentData) {
        return mimeMessageToEmailBuilder(mimeMessage, pkcs12Config, openPgpReceiveConfig, fetchAttachmentData, false);
    }

    private EmailPopulatingBuilder mimeMessageToEmailBuilder(final MimeMessage mimeMessage, final Pkcs12Config pkcs12Config,
            final OpenPgpReceiveConfig openPgpReceiveConfig, final boolean fetchAttachmentData, final boolean generatedArtifact) {
		checkNonEmptyArgument(mimeMessage, "mimeMessage");
		requireSmimeForExplicitDecryption(pkcs12Config);
        final InputInspection originalInspection =
                InputInspection.capture(mimeMessage, generatedArtifact);
		final InboundProtectionResult<OriginalOpenPgpDetails> openPgpResult = preprocessOpenPgp(mimeMessage, openPgpReceiveConfig);
		final SmimePreprocessingResult smimeResult = openPgpResult.isRecognized()
				? null
				: preprocessSmime(openPgpResult.getEffectiveMimeMessage(), pkcs12Config);
		final MimeMessage effectiveMessage = smimeResult != null && smimeResult.isRecognized()
				? smimeResult.getEffectiveMimeMessage()
				: openPgpResult.getEffectiveMimeMessage();
		val builder = emailStartingBuilder.startingBlank();
        final InputInspection contentInspection = effectiveMessage == mimeMessage
                ? originalInspection : InputInspection.capture(effectiveMessage, generatedArtifact, EmailSourceData.Representation.EXTRACTED);
        if (smimeResult != null && smimeResult.getDeclarationFailure() != null) {
            contentInspection.noteUnreadableContentType(smimeResult.getDeclarationFailure());
        }
		val parsed = MimeMessageParser.parseMimeMessage(effectiveMessage, fetchAttachmentData, contentInspection);
		val emailBuilder = MimeMessageEmailMapper.populate(builder, parsed);
        final InternalEmailPopulatingBuilder inspectionBuilder = (InternalEmailPopulatingBuilder) emailBuilder;
        if (effectiveMessage != mimeMessage) {
            inspectionBuilder.retainInspection(originalInspection.getSourceData(), originalInspection.getProblems());
            InspectionAssembly.attach(inspectionBuilder, contentInspection.getSourceData(), contentInspection.getProblems());
        }
		((InternalEmailPopulatingBuilder) emailBuilder).withOriginalOpenPgpDetails(openPgpResult.getDetails());
		if (openPgpResult.isRecognized()) {
            InspectionAssembly.protection(inspectionBuilder, originalInspection::getOrigin);
			return emailBuilder;
		}
		if (smimeResult != null && smimeResult.isRecognized()) {
			final boolean detachedSignatureUnwrapped = effectiveMessage != openPgpResult.getEffectiveMimeMessage()
					&& "multipart/signed".equalsIgnoreCase(smimeResult.getDetails().getSmimeMime());
            final List<AttachmentResource> clearAttachments = emailBuilder.getAttachments();
            applySmimePreprocessingResult(emailBuilder, smimeResult, detachedSignatureUnwrapped);
            if (detachedSignatureUnwrapped && !parsed.isRootRepresentedAsResource()) {
                processIndependentSmimeAttachments(inspectionBuilder, clearAttachments, smimeResult.getDecryptedArtifacts(),
                        pkcs12Config, parsed::getAttachmentSource);
            }
            InspectionAssembly.protection(inspectionBuilder, originalInspection::getOrigin);
            return emailBuilder;
		}
        if (smimeResult != null && smimeResult.getDeclarationFailure() != null) {
            inspectionBuilder.withOriginalSmimeDetails(smimeResult.getDetails());
            processIndependentSmimeAttachments(inspectionBuilder, emailBuilder.getAttachments(), java.util.Collections.emptyList(),
                    pkcs12Config, parsed::getAttachmentSource);
        } else {
            decryptAttachments(emailBuilder, effectiveMessage, pkcs12Config, parsed::getAttachmentSource);
        }
        InspectionAssembly.protection(inspectionBuilder, originalInspection::getOrigin);
        return emailBuilder;
	}

    private static void processIndependentSmimeAttachments(final InternalEmailPopulatingBuilder builder,
            final List<AttachmentResource> attachments, final List<AttachmentResource> parentArtifacts,
            @Nullable final Pkcs12Config pkcs12Config, final Function<AttachmentResource, EmailSourcePart> sourceOf) {
        final List<AttachmentDecryptionResult> results = loadSmimeModule().processIndependentAttachments(attachments, pkcs12Config);
        final List<AttachmentResource> processed = new ArrayList<>(parentArtifacts);
        for (final AttachmentDecryptionResult result : results) {
            processed.add(result.getAttachmentResource());
        }
        builder.clearDecryptedAttachments().withDecryptedAttachments(processed);
        InspectionAssembly.attachmentProtection(builder, results, sourceOf);
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
	public EmailPopulatingBuilder emlToEmailBuilder(@NotNull final Path emlPath,
			@Nullable final Pkcs12Config pkcs12Config,
			@Nullable final OpenPgpReceiveConfig openPgpReceiveConfig,
			@NotNull final Session session) {
		EmailPopulatingBuilder parsed = null;
		try (InputStream emlInputStream = Files.newInputStream(requireNonNull(emlPath, "emlPath"))) {
			parsed = emlToEmailBuilder(emlInputStream, pkcs12Config, openPgpReceiveConfig, session);
		} catch (IOException e) {
			if (parsed != null) {
				noteInputCloseFailure(parsed, e);
				return parsed;
			}
			return sourceFailureBuilder(new SourceReadException(EmailSourceData.Format.MIME, null, false,
					EmailProblem.Code.CONTENT_READ_FAILED, e));
		}
		return parsed;
	}

	/** Reads caller-owned EML into the existing byte-preserving parsing path. */
	@NotNull
	public EmailPopulatingBuilder emlToEmailBuilder(@NotNull final InputStream emlInputStream,
			@Nullable final Pkcs12Config pkcs12Config,
			@Nullable final OpenPgpReceiveConfig openPgpReceiveConfig,
			@NotNull final Session session) {
		try {
			return mimeMessageToEmailBuilder(readPreservedEml(emlInputStream, session),
					pkcs12Config, openPgpReceiveConfig, EmailStartingBuilder.DEFAULT_FETCH_ATTACHMENT_DATA);
		} catch (SourceReadException failure) {
			return sourceFailureBuilder(failure);
		}
	}

	/** Internal Outlook import shared by fluent conversion and the released static entry points. */
	@NotNull
	public OutlookEmailConversionResult outlookMsgToEmailBuilderWithOutlookData(@NotNull final String msgFileName,
			@Nullable final Pkcs12Config pkcs12Config) {
		checkNonEmptyArgument(msgFileName, "msgFile");
		return outlookMsgToEmailBuilderWithOutlookData(Path.of(msgFileName), pkcs12Config);
	}

	/** Internal Outlook import shared by fluent conversion and the released static entry points. */
	@NotNull
	public OutlookEmailConversionResult outlookMsgToEmailBuilderWithOutlookData(@NotNull final File msgFile,
			@Nullable final Pkcs12Config pkcs12Config) {
		checkNonEmptyArgument(msgFile, "msgFile");
		return outlookMsgToEmailBuilderWithOutlookData(msgFile.toPath(), pkcs12Config);
	}

	/** Internal Outlook Path import; closes the stream opened here. */
	@NotNull
	public OutlookEmailConversionResult outlookMsgToEmailBuilderWithOutlookData(@NotNull final Path msgPath,
			@Nullable final Pkcs12Config pkcs12Config) {
		requireNonNull(msgPath, "msgPath");
		requireSmimeForExplicitDecryption(pkcs12Config);
		ModuleLoader.loadOutlookModule();
		try {
			// The stream overload retains the specialist reader's established ownership for this internally opened stream.
			final InputStream msgInputStream = Files.newInputStream(msgPath);
			return outlookMsgToEmailBuilderWithOutlookData(msgInputStream, pkcs12Config);
		} catch (IOException e) {
			return sourceFailureOutlook(new SourceReadException(EmailSourceData.Format.OUTLOOK_MSG, null, false,
					EmailProblem.Code.CONTENT_READ_FAILED, e));
		}
	}

	/** Internal Outlook stream import. The fluent facade shields caller streams from the parser's established close behavior. */
	@NotNull
	public OutlookEmailConversionResult outlookMsgToEmailBuilderWithOutlookData(@NotNull final InputStream msgInputStream,
			@Nullable final Pkcs12Config pkcs12Config) {
		requireNonNull(msgInputStream, "msgInputStream");
		requireSmimeForExplicitDecryption(pkcs12Config);
		ModuleLoader.loadOutlookModule();
		byte[] captured = null;
		OutlookEmailConversionResult parsed = null;
		try (InputStream ownedInput = msgInputStream) {
			captured = SourceReadException.acquire(ownedInput, EmailSourceData.Format.OUTLOOK_MSG);
			parsed = toOutlookEmailConversionResult(loadOutlookMessage(new ByteArrayInputStream(captured), pkcs12Config), pkcs12Config);
		} catch (SourceReadException failure) {
			return sourceFailureOutlook(failure);
		} catch (OutlookInputException parseFailure) {
			return sourceFailureOutlook(new SourceReadException(EmailSourceData.Format.OUTLOOK_MSG, captured, true,
					EmailProblem.Code.FIELD_NOT_INTERPRETED, parseFailure));
		} catch (IOException closingFailure) {
			if (parsed != null) {
				noteInputCloseFailure(parsed.getEmailBuilder(), closingFailure);
				return parsed;
			}
			return sourceFailureOutlook(new SourceReadException(EmailSourceData.Format.OUTLOOK_MSG, captured, captured != null,
					EmailProblem.Code.CONTENT_READ_FAILED, closingFailure));
		}
		return parsed;
	}

	private OutlookEmailConversionResult sourceFailureOutlook(final SourceReadException failure) {
		final EmailPopulatingBuilder builder = sourceFailureBuilder(failure);
		return new FailedOutlookInput(builder, failure);
	}

	static void noteInputCloseFailure(final EmailPopulatingBuilder value, final IOException failure) {
		org.simplejavamail.internal.util.InterruptedIoGuard.rethrowIfInterrupted(failure);
		final InternalEmailPopulatingBuilder builder = (InternalEmailPopulatingBuilder) value;
		final EmailSourceData source = builder.getSourceData();
		final List<EmailProblem> observations = new ArrayList<>(builder.getProblems());
		observations.add(EmailInspection.problem(source.getOrigin(), EmailProblem.Operation.IMPORT, EmailProblem.Code.RESOURCE_CLOSE_FAILED,
				"source/input-close", null, failure.getMessage() != null ? failure.getMessage() : "The input stream could not be closed",
				source.getParts().isEmpty() ? java.util.Collections.emptyList() : singletonList(source.getParts().get(0))));
		builder.retainInspection(source, observations);
	}

	private static void noteSuppressedInputCloseFailures(final EmailPopulatingBuilder builder, final SourceReadException failure) {
		for (Throwable suppressed : failure.getSuppressed()) {
			if (suppressed instanceof IOException) noteInputCloseFailure(builder, (IOException) suppressed);
		}
		if (failure.getCause() != null) {
			for (Throwable suppressed : failure.getCause().getSuppressed()) {
				if (suppressed instanceof IOException) noteInputCloseFailure(builder, (IOException) suppressed);
			}
		}
	}

	static SourceReadException acquisitionFailure(final OutlookEmailConversionResult result) {
		return result instanceof FailedOutlookInput ? ((FailedOutlookInput) result).failure : null;
	}

	/** Keeps the selected MSG import's recognized failure at a combined artifact boundary, without gating historical Email Problems. */
	@NotNull
	public static Email buildOutlookArtifactInput(@NotNull final OutlookEmailConversionResult imported) {
		final SourceReadException failure = acquisitionFailure(imported);
		if (failure != null) {
			throw failure;
		}
		return imported.buildEmail();
	}

	private static final class FailedOutlookInput implements OutlookEmailConversionResult {
		private final EmailPopulatingBuilder builder;
		private final SourceReadException failure;
		private final OutlookMessageData metadata = InspectionOutlookData.builder().build();

		private FailedOutlookInput(final EmailPopulatingBuilder builder, final SourceReadException failure) {
			this.builder = builder;
			this.failure = failure;
		}

		@Override public EmailPopulatingBuilder getEmailBuilder() { return builder; }
		@Override public OutlookMessageData getOutlookMessageData() { return metadata; }
		@Override public Email buildEmail() { return builder.buildEmail(); }
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
			@NotNull final SmimePreprocessingResult result,
			final boolean detachedSignatureUnwrapped) {
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
			// An unwrapped signature is outside the parsed clear entity, even when an ordinary file has the same name.
			// Fallback parsing still visits the protected entity, so retain its existing representation handling.
			if (detachedSignatureUnwrapped || !hasAttachmentNamed(emailBuilder.getAttachments(), protectedAttachment.getName())) {
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
			final NestedContent nestedContent = emlAttachmentToEmailBuilder(effectiveMessageArtifact);
			final EmailPopulatingBuilder nestedBuilder = nestedContent.builder;
			if (result.getNestedSignedDetails() != null) {
				((InternalEmailPopulatingBuilder) nestedBuilder).withOriginalSmimeDetails(result.getNestedSignedDetails());
			}
            final Email nested = nestedBuilder.buildEmail();
			if (nestedContent.materialized) internal.withSmimeSignedEmail(nested);
            InspectionAssembly.attach(internal, nested.getSourceData(), nested.getProblems());
		}
		return emailBuilder;
	}

	@NotNull
	private NestedContent emlAttachmentToEmailBuilder(@NotNull final AttachmentResource attachment) {
		EmailPopulatingBuilder parsed = null;
		boolean materialized = false;
		try (InputStream input = attachment.openStream()) {
            parsed = mimeMessageToEmailBuilder(readPreservedEml(input, createDummySession()), null, null,
                    EmailStartingBuilder.DEFAULT_FETCH_ATTACHMENT_DATA, true);
			materialized = true;
		} catch (SourceReadException failure) {
			parsed = sourceFailureBuilder(failure);
		} catch (IOException e) {
			if (parsed != null) {
				noteInputCloseFailure(parsed, e);
			} else {
				parsed = sourceFailureBuilder(new SourceReadException(EmailSourceData.Format.MIME, null, false,
						EmailProblem.Code.CONTENT_READ_FAILED, e));
			}
		}
		final InternalEmailPopulatingBuilder inspection = (InternalEmailPopulatingBuilder) parsed;
		final EmailSourceData source = inspection.getSourceData();
		if (source.getRepresentation() == EmailSourceData.Representation.RECEIVED) {
			inspection.retainInspection(EmailInspection.source(source.getOrigin(), source.getFormat(), EmailSourceData.Representation.GENERATED,
					source.getHeaders(), source.getProperties(), source.getParts(), source.getDerivedSources()), inspection.getProblems());
		}
		return new NestedContent(parsed, materialized);
	}

	private static final class NestedContent {
		private final EmailPopulatingBuilder builder;
		private final boolean materialized;

		private NestedContent(final EmailPopulatingBuilder builder, final boolean materialized) {
			this.builder = builder;
			this.materialized = materialized;
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

	/** Populate input facts without applying readable-content replacements or ordinary attachment processing to exact mail. */
	static void populateExactProtectionDetails(@NotNull final InternalEmailPopulatingBuilder builder,
			@NotNull final MimeMessage mimeMessage) {
		final OriginalSmimeDetails declaration = UnavailableSmimeInspection.fromMessage(mimeMessage);
		// Recognizable but ambiguous PKCS7 declarations can still be understood by the available feature implementation.
		final InboundProtectionResult<OriginalSmimeDetails> smime = declaration.getSmimeMime() != null && ModuleLoader.smimeModuleAvailable()
				? loadSmimeModule().inspectIncomingMessage(mimeMessage.getSession() != null ? mimeMessage.getSession() : createDummySession(), mimeMessage)
				: null;
		builder.withOriginalSmimeDetails(smime != null && smime.isRecognized() ? smime.getDetails() : declaration);
		builder.withOriginalOpenPgpDetails(preprocessOpenPgp(mimeMessage, null, true).getDetails());
	}

	@NotNull
	private static InboundProtectionResult<OriginalOpenPgpDetails> preprocessOpenPgp(@NotNull final MimeMessage mimeMessage,
			@Nullable final OpenPgpReceiveConfig openPgpReceiveConfig) {
		return preprocessOpenPgp(mimeMessage, openPgpReceiveConfig, false);
	}

	@NotNull
	private static InboundProtectionResult<OriginalOpenPgpDetails> preprocessOpenPgp(@NotNull final MimeMessage mimeMessage,
			@Nullable final OpenPgpReceiveConfig openPgpReceiveConfig, final boolean recognizedEnvelopeOnly) {
		try {
			if (recognizedEnvelopeOnly) {
				// Plain exact mail needs neither optional-module initialization nor another serialized copy of its source.
				new ContentType(mimeMessage.getContentType());
				if (!isOpenPgpMime(mimeMessage)) {
					return UnprocessedOpenPgpMail.builder().recognized(false).effectiveMimeMessage(mimeMessage)
							.details(OpenPgpDetailsImpl.plain()).build();
				}
			}
			final Session session = mimeMessage.getSession() != null ? mimeMessage.getSession() : createDummySession();
			if (ModuleLoader.openPgpModuleAvailable() || openPgpReceiveConfig != null) {
				return ModuleLoader.loadOpenPgpModule().processIncoming(
						session, mimeMessage, openPgpReceiveConfig);
			}
			final boolean protectedWithOpenPgp = isOpenPgpMime(mimeMessage);
			if (protectedWithOpenPgp) {
				final boolean signed = mimeMessage.isMimeType("multipart/signed");
				return UnprocessedOpenPgpMail.builder()
						.recognized(true)
						.effectiveMimeMessage(mimeMessage)
					.details(OpenPgpDetailsImpl.builder()
								.openPgpMode(signed ? OpenPgpMode.SIGNED : OpenPgpMode.ENCRYPTED)
								.signatureStatus(SignatureStatus.UNKNOWN)
								.decryptionStatus(signed && !recognizedEnvelopeOnly
										? DecryptionStatus.NOT_ENCRYPTED : DecryptionStatus.UNKNOWN)
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
			org.simplejavamail.internal.util.InterruptedIoGuard.rethrowIfInterrupted(e);
			return UnprocessedOpenPgpMail.builder()
					.recognized(false)
					.effectiveMimeMessage(mimeMessage)
				.details(OpenPgpDetailsImpl.builder()
						.openPgpMode(OpenPgpMode.UNKNOWN)
						.signatureStatus(SignatureStatus.UNKNOWN)
						.decryptionStatus(DecryptionStatus.UNKNOWN)
						.failureReason("Unable to inspect OpenPGP/MIME structure")
						.build())
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
        final InternalEmailPopulatingBuilder builder = (InternalEmailPopulatingBuilder) result.getEmailBuilder();
        final InputInspection inspection =
                InputInspection.capture(result.getOutlookMessageData(), builder.getId());
        inspection.noteAddressRecovery();
        inspection.noteUninterpretedDate(builder.getSentDate());
        builder.retainInspection(inspection.getSourceData(), inspection.getProblems());
		final SmimeParseResult attachmentInspection = decryptAttachments(result.getEmailBuilder(), result.getOutlookMessage(), pkcs12Config);
        InspectionAssembly.protection(builder, inspection::getOrigin);
        final List<EmailProblem> capturedProblems = new ArrayList<>(builder.getProblems());
        builder.retainInspection(OutlookSourceCapture.capture(builder.getSourceData(), result.getOutlookMessage(), builder, capturedProblems,
                        outlookAttachmentFailures(attachmentInspection, result.getAttachmentSourceIndices())),
                capturedProblems);
        if (Objects.equals(builder.getHTMLText(), result.getOutlookMessage().getBodyHTML())) {
            builder.retainReceivedHtml(builder.getHTMLText(), OutlookSourceCapture.originalHtmlParts(builder.getSourceData(), result.getOutlookMessage()));
        }
        final OutlookMessageData data = InspectionOutlookData.copyOf(result.getOutlookMessageData());
        return new OutlookEmailConversionResult() {
            @Override public EmailPopulatingBuilder getEmailBuilder() { return builder; }
            @Override public OutlookMessageData getOutlookMessageData() { return data; }
            @Override public Email buildEmail() { return builder.buildEmail(); }
        };
	}

	private SmimeParseResult decryptAttachments(final EmailPopulatingBuilder emailBuilder,
			final OutlookMessage outlookMessage,
			@Nullable final Pkcs12Config pkcs12Config) {
		if (ModuleLoader.smimeModuleAvailable()) {
			SmimeParseResult smimeParseResult = loadSmimeModule().decryptAttachments(emailBuilder.getAttachments(), outlookMessage, pkcs12Config);
			handleSmimeParseResult((InternalEmailPopulatingBuilder) emailBuilder, smimeParseResult);
			updateEmailIfBothSignedAndEncrypted(emailBuilder, smimeParseResult);
			return smimeParseResult;
		} else {
			((InternalEmailPopulatingBuilder) emailBuilder).withOriginalSmimeDetails(UnavailableSmimeInspection.fromOutlook(outlookMessage));
		}
		return null;
	}

	private static Map<Integer, String> outlookAttachmentFailures(final SmimeParseResult inspection,
			final Map<DataSource, Integer> sourceIndices) {
		final Map<Integer, String> failures = new LinkedHashMap<>();
		if (inspection != null) {
			for (final AttachmentDecryptionResult attachment : inspection.getDecryptedAttachmentResults()) {
				if (attachment.getFailureReason() != null) {
					final Integer index = sourceIndices.get(attachment.getAttachmentResource().getDataSource());
					if (index != null) {
						failures.put(index, attachment.getFailureReason());
					}
				}
			}
		}
		return failures;
	}

	@NotNull
	private EmailPopulatingBuilder decryptAttachments(final EmailPopulatingBuilder emailBuilder,
			final MimeMessage mimeMessage,
			@Nullable final Pkcs12Config pkcs12Config,
			final Function<AttachmentResource, EmailSourcePart> sourceOf) {
		if (ModuleLoader.smimeModuleAvailable()) {
			SmimeParseResult smimeParseResult = loadSmimeModule().decryptAttachments(emailBuilder.getAttachments(), mimeMessage, pkcs12Config);
			handleSmimeParseResult((InternalEmailPopulatingBuilder) emailBuilder, smimeParseResult);
			InspectionAssembly.attachmentProtection((InternalEmailPopulatingBuilder) emailBuilder, smimeParseResult, sourceOf);
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
            final NestedContent nestedContent = emlAttachmentToEmailBuilder(smimeParseResult.getSmimeSignedOrEncryptedEmail());
            final Email nested = nestedContent.builder.buildEmail();
            if (nestedContent.materialized) emailBuilder.withSmimeSignedEmail(nested);
            InspectionAssembly.attach(emailBuilder, nested.getSourceData(), nested.getProblems());
		}
	}

	private static Session createDummySession() {
		return JakartaMailImplementation.createSession(new Properties());
	}

}
