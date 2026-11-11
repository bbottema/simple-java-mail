package org.simplejavamail.converter.internal;

import org.simplejavamail.internal.smime.FactorySmimeRuntime;

import org.simplejavamail.api.outlook.OutlookMessageData;

import jakarta.mail.MessagingException;
import jakarta.mail.Session;
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
import org.jetbrains.annotations.ApiStatus;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.simplejavamail.api.SimpleJavaMail;
import org.simplejavamail.api.converter.ConfiguredEmailConverter;
import org.simplejavamail.api.converter.config.ImportLimitsConfig;
import org.simplejavamail.api.email.AttachmentResource;
import org.simplejavamail.api.email.Email;
import org.simplejavamail.api.email.EmailPopulatingBuilder;
import org.simplejavamail.api.email.EmailStartingBuilder;
import org.simplejavamail.api.email.EmailProblem;
import org.simplejavamail.api.email.EmailSourceData;
import org.simplejavamail.api.email.config.EmailTemplateOptions;
import org.simplejavamail.api.internal.outlooksupport.model.OutlookInputException;
import org.simplejavamail.internal.email.EmailInspection;
import org.simplejavamail.internal.email.InspectionOutlookData;
import org.simplejavamail.api.email.config.OpenPgpReceiveConfig;
import org.simplejavamail.api.internal.outlooksupport.model.EmailFromOutlookMessage;
import org.simplejavamail.api.mailer.config.Pkcs12Config;
import org.simplejavamail.api.outlook.OutlookEmailConversionResult;
import org.simplejavamail.config.SimpleJavaMailConfig;
import org.simplejavamail.converter.EmailConverter;
import org.simplejavamail.email.internal.EmailPopulatingBuilderFactoryImpl;
import org.simplejavamail.email.internal.EmailStartingBuilderImpl;
import org.simplejavamail.email.internal.InternalEmailPopulatingBuilder;
import org.simplejavamail.internal.moduleloader.ModuleLoader;
import org.simplejavamail.internal.util.FinalizedMimeMessage;
import org.simplejavamail.internal.util.JakartaMailImplementation;

import static java.nio.charset.StandardCharsets.UTF_8;
import static java.util.Collections.singletonList;
import static java.util.Objects.requireNonNull;
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
    private final SimpleJavaMailConfig configuration;
    private final FactorySmimeRuntime smimeRuntime;

	/**
	 * Creates a converter for an immutable configuration snapshot.
	 *
	 * @param config The snapshot retained by builders produced during conversion.
	 * @see SimpleJavaMail#converter()
	 */
	public ConfiguredEmailConverterImpl(@NotNull final SimpleJavaMailConfig config) {
        this(config, new FactorySmimeRuntime(config));
    }

    public ConfiguredEmailConverterImpl(@NotNull final SimpleJavaMailConfig config, @NotNull final FactorySmimeRuntime smimeRuntime) {
        this.smimeRuntime = requireNonNull(smimeRuntime, "smimeRuntime");
		final SimpleJavaMailConfig requiredConfig = requireNonNull(config, "config");
        this.configuration = requiredConfig;
		this.emailStartingBuilder = new EmailStartingBuilderImpl(requiredConfig, smimeRuntime);
		this.emailPopulatingBuilderFactory = new EmailPopulatingBuilderFactoryImpl(requiredConfig);
	}

    /** The originating factory owner is retained separately from every operation budget and output snapshot. */
    public FactorySmimeRuntime smimeRuntime() { return smimeRuntime; }

    /** Static artifact routes keep their no-template policy while retaining their conventional factory owner. */
    public ConfiguredEmailConverterImpl forArtifactExport() {
        return new ConfiguredEmailConverterImpl(org.simplejavamail.config.ConfigLoader.builder().load(), smimeRuntime);
    }

    ImportContext prepareImport(final ImportLimitsConfig choices, final boolean rawSource) {
        return ImportContext.prepare(configuration, choices, rawSource);
    }

    private ImportContext prepareImport(final boolean rawSource) {
        return prepareImport(ImportLimitsConfig.builder().build(), rawSource);
    }

	/** @see ConfiguredEmailConverter#fromEmail(Email) */
	@Override
	public EmailConversion fromEmail(@NotNull final Email email) {
		return new FluentEmailConversions.FromEmail(this, requireNonNull(email, "email"));
	}

	/** Creates an editable output snapshot without consulting another factory or changing the input. */
	EmailPopulatingBuilder copyForOutput(final Email email) {
		return new EmailStartingBuilderImpl(org.simplejavamail.config.ConfigLoader.builder().load(), smimeRuntime).copying(email);
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
				(session, context) -> readPreservedEml(new ByteArrayInputStream(captured), session, context.maximumInputBytes));
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
				(session, context) -> readPreservedEmlPath(captured, session, context.maximumInputBytes));
	}

	/** @see ConfiguredEmailConverter#fromEml(InputStream) */
	@Override
	public EmlConversion fromEml(@NotNull final InputStream emlInputStream) {
		final InputStream captured = requireNonNull(emlInputStream, "emlInputStream");
		return new FluentEmailConversions.FromEml(this,
				session -> parseEmlInput(captured, session),
				(session, context) -> readPreservedEml(captured, session, context.maximumInputBytes));
	}

	/** @see ConfiguredEmailConverter#fromOutlookMsg(String) */
	@Override
	public OutlookConversion fromOutlookMsg(@NotNull final String msgFileName) {
		final String captured = requireNonNull(msgFileName, "msgFileName");
		return new FluentEmailConversions.FromOutlook(this, (pkcs12, context) -> {
            checkNonEmptyArgument(captured, "msgFile");
            return outlookMsgToEmailBuilderWithOutlookData(Path.of(captured), pkcs12, context);
        });
	}

	/** @see ConfiguredEmailConverter#fromOutlookMsg(File) */
	@Override
	public OutlookConversion fromOutlookMsg(@NotNull final File msgFile) {
		final File captured = requireNonNull(msgFile, "msgFile");
		return new FluentEmailConversions.FromOutlook(this, (pkcs12, context) -> outlookMsgToEmailBuilderWithOutlookData(captured.toPath(), pkcs12, context));
	}

	/** @see ConfiguredEmailConverter#fromOutlookMsg(Path) */
	@Override
	public OutlookConversion fromOutlookMsg(@NotNull final Path msgPath) {
		final Path captured = requireNonNull(msgPath, "msgPath");
		return new FluentEmailConversions.FromOutlook(this, (pkcs12, context) -> outlookMsgToEmailBuilderWithOutlookData(captured, pkcs12, context));
	}

	/** @see ConfiguredEmailConverter#fromOutlookMsg(InputStream) */
	@Override
	public OutlookConversion fromOutlookMsg(@NotNull final InputStream msgInputStream) {
		final InputStream captured = requireNonNull(msgInputStream, "msgInputStream");
		return new FluentEmailConversions.FromOutlook(this, (pkcs12, context) -> outlookMsgToEmailBuilderWithOutlookData(leavingOpen(captured), pkcs12, context));
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

	private static MimeMessage readPreservedEmlPath(final Path path, final Session session, final long maximumBytes) {
		MimeMessage parsed = null;
		try (InputStream input = Files.newInputStream(path)) {
			parsed = readPreservedEml(input, session, maximumBytes);
		} catch (IOException e) {
			if (parsed != null) throw new SourceCloseException(parsed, e);
			throw new SourceReadException(EmailSourceData.Format.MIME, null, false, EmailProblem.Code.CONTENT_READ_FAILED, e);
		}
		return parsed;
	}

	private static MimeMessage readPreservedEml(final InputStream input, final Session session) {
        return readPreservedEml(input, session, 0);
    }

    private static MimeMessage readPreservedEml(final InputStream input, final Session session, final long maximumBytes) {
        final Session requiredSession = checkNonEmptyArgument(session, "session");
		final byte[] emlBytes = SourceReadException.acquire(checkNonEmptyArgument(input, "emlInputStream"), EmailSourceData.Format.MIME, maximumBytes);
		try {
			// Protection inspection must retain the received representation, including line endings tolerated by the MIME parser.
			return FinalizedMimeMessage.fromOwnedBytes(
                    requiredSession, emlBytes, FinalizedMimeMessage.ProtectionState.NONE, false);
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
        if (!failure.getSourceData().getParts().isEmpty()) {
            final org.simplejavamail.api.email.EmailSourceContent captured = failure.getSourceData().getParts().get(0).getContent();
            if (captured != null && captured.isFullyCaptured()) {
                final boolean msg = failure.getSourceData().getFormat() == EmailSourceData.Format.OUTLOOK_MSG;
                ((InternalEmailPopulatingBuilder) builder).retainSelectionInspection(org.simplejavamail.internal.email.ReceivedEmailInspection.builder()
                        .originalMessage(AttachmentResource.builder().withName(msg ? "message.msg" : "message.eml")
                                .withDataSource(org.simplejavamail.internal.util.AttachmentDataSources.fromCapturedContent(captured,
                                        msg ? "message.msg" : "message.eml", msg ? "application/vnd.ms-outlook" : "message/rfc822")).build())
                        .originalMessageProtection(org.simplejavamail.internal.email.OriginalMessageProtection.AS_RECEIVED).build());
            }
        }
		noteSuppressedInputCloseFailures(builder, failure);
		return builder;
	}

	/** Internal import shared by the fluent source stages and the released static conversion API. */
	@NotNull
	public EmailPopulatingBuilder mimeMessageToEmailBuilder(@NotNull final MimeMessage mimeMessage,
			@Nullable final Pkcs12Config pkcs12Config,
			@Nullable final OpenPgpReceiveConfig openPgpReceiveConfig,
			final boolean fetchAttachmentData) {
        return mimeMessageToEmailBuilder(mimeMessage, pkcs12Config, openPgpReceiveConfig, fetchAttachmentData, false, prepareImport(false));
    }

    EmailPopulatingBuilder mimeMessageToEmailBuilder(final MimeMessage mimeMessage, final Pkcs12Config pkcs12Config,
            final OpenPgpReceiveConfig openPgpReceiveConfig, final boolean fetchAttachmentData, final ImportContext context) {
        return mimeMessageToEmailBuilder(mimeMessage, pkcs12Config, openPgpReceiveConfig, fetchAttachmentData, false, context);
    }

    private EmailPopulatingBuilder mimeMessageToEmailBuilder(final MimeMessage mimeMessage, final Pkcs12Config pkcs12Config,
            final OpenPgpReceiveConfig openPgpReceiveConfig, final boolean fetchAttachmentData, final boolean generatedArtifact,
            final ImportContext context) {
        checkNonEmptyArgument(mimeMessage, "mimeMessage");
        requireSmimeForExplicitDecryption(pkcs12Config);
        if (openPgpReceiveConfig != null) ModuleLoader.loadOpenPgpModule();
        return new LogicalMimeReader(this, emailStartingBuilder, smimeRuntime, pkcs12Config, openPgpReceiveConfig, fetchAttachmentData)
                .read(mimeMessage, context);
    }

    EmailPopulatingBuilder inspectExact(final MimeMessage message) {
        return new LogicalMimeReader(this, emailStartingBuilder, smimeRuntime, null, null, false).readExact(message);
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
        checkNonEmptyArgument(session, "session");
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
        final ImportContext context = prepareImport(true);
		try {
			return mimeMessageToEmailBuilder(readPreservedEml(emlInputStream, session, context.maximumInputBytes),
					pkcs12Config, openPgpReceiveConfig, EmailStartingBuilder.DEFAULT_FETCH_ATTACHMENT_DATA, context);
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
        return outlookMsgToEmailBuilderWithOutlookData(msgPath, pkcs12Config, prepareImport(true));
    }

    private OutlookEmailConversionResult outlookMsgToEmailBuilderWithOutlookData(final Path msgPath,
            final Pkcs12Config pkcs12Config, final ImportContext context) {
		requireNonNull(msgPath, "msgPath");
		requireSmimeForExplicitDecryption(pkcs12Config);
		ModuleLoader.loadOutlookModule();
		try {
			// The stream overload retains the specialist reader's established ownership for this internally opened stream.
			final InputStream msgInputStream = Files.newInputStream(msgPath);
			return outlookMsgToEmailBuilderWithOutlookData(msgInputStream, pkcs12Config, context);
		} catch (IOException e) {
			return sourceFailureOutlook(new SourceReadException(EmailSourceData.Format.OUTLOOK_MSG, null, false,
					EmailProblem.Code.CONTENT_READ_FAILED, e));
		}
	}

	/** Internal Outlook stream import. The fluent facade shields caller streams from the parser's established close behavior. */
	@NotNull
	public OutlookEmailConversionResult outlookMsgToEmailBuilderWithOutlookData(@NotNull final InputStream msgInputStream,
			@Nullable final Pkcs12Config pkcs12Config) {
        return outlookMsgToEmailBuilderWithOutlookData(msgInputStream, pkcs12Config, prepareImport(true));
    }

    OutlookEmailConversionResult outlookMsgToEmailBuilderWithOutlookData(final InputStream msgInputStream,
            final Pkcs12Config pkcs12Config, final ImportContext context) {
        return outlookMsgToEmailBuilderWithOutlookData(msgInputStream, pkcs12Config, context,
                org.simplejavamail.converter.internal.mimemessage.ProtectionScope.plain(), null, null,
                org.simplejavamail.internal.email.OriginalMessageProtection.AS_RECEIVED);
    }

    OutlookEmailConversionResult outlookMsgToEmailBuilderWithOutlookData(final InputStream msgInputStream,
            final Pkcs12Config pkcs12Config, final ImportContext context,
            final org.simplejavamail.converter.internal.mimemessage.ProtectionScope enclosing, final AttachmentResource suppliedOriginal,
            final OpenPgpReceiveConfig pgpKeys, final org.simplejavamail.internal.email.OriginalMessageProtection originalProtection) {
		requireNonNull(msgInputStream, "msgInputStream");
		requireSmimeForExplicitDecryption(pkcs12Config);
		ModuleLoader.loadOutlookModule();
		byte[] captured = null;
		OutlookEmailConversionResult parsed = null;
		try (InputStream ownedInput = msgInputStream) {
			captured = SourceReadException.acquire(ownedInput, EmailSourceData.Format.OUTLOOK_MSG, context.maximumInputBytes);
            parsed = new LogicalOutlookReader(this, emailStartingBuilder, smimeRuntime, pkcs12Config, pgpKeys)
                    .read(loadOutlookMessage(new ByteArrayInputStream(captured), pkcs12Config, context, enclosing, pgpKeys), context,
                            suppliedOriginal != null ? suppliedOriginal : AttachmentResource.builder().withName("message.msg").withDataSource(
                                    org.simplejavamail.internal.util.AttachmentDataSources.fromOwnedBytes(captured, "message.msg", "application/vnd.ms-outlook")).build(), enclosing, originalProtection);
		} catch (SourceReadException failure) {
			return retainOriginalChoice(sourceFailureOutlook(failure), suppliedOriginal, originalProtection);
		} catch (OutlookInputException parseFailure) {
			return retainOriginalChoice(sourceFailureOutlook(new SourceReadException(EmailSourceData.Format.OUTLOOK_MSG, captured, true,
					EmailProblem.Code.FIELD_NOT_INTERPRETED, parseFailure)), suppliedOriginal, originalProtection);
		} catch (IOException closingFailure) {
			if (parsed != null) {
				noteInputCloseFailure(parsed.getEmailBuilder(), closingFailure);
				return parsed;
			}
			return retainOriginalChoice(sourceFailureOutlook(new SourceReadException(EmailSourceData.Format.OUTLOOK_MSG, captured, captured != null,
					EmailProblem.Code.CONTENT_READ_FAILED, closingFailure)), suppliedOriginal, originalProtection);
		}
		return parsed;
	}

	private OutlookEmailConversionResult sourceFailureOutlook(final SourceReadException failure) {
		final EmailPopulatingBuilder builder = sourceFailureBuilder(failure);
		return new FailedOutlookInput(builder, failure);
	}

    private static OutlookEmailConversionResult retainOriginalChoice(final OutlookEmailConversionResult result, final AttachmentResource supplied,
            final org.simplejavamail.internal.email.OriginalMessageProtection protection) {
        final InternalEmailPopulatingBuilder builder = (InternalEmailPopulatingBuilder) result.getEmailBuilder();
        final org.simplejavamail.internal.email.ReceivedEmailInspection inspection = org.simplejavamail.internal.email.ReceivedEmailInspection
                .copyOf(builder.getSelectionInspection(), builder.getAttachments().size(), builder.getEmbeddedImages().size());
        final AttachmentResource original = supplied != null ? supplied : inspection.getOriginalMessage();
        builder.retainSelectionInspection(inspection.toBuilder().originalMessage(original).originalMessageProtection(original == null
                ? org.simplejavamail.internal.email.OriginalMessageProtection.UNKNOWN : protection).build());
        return result;
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

	@NotNull
	private EmailFromOutlookMessage loadOutlookMessage(@NotNull final InputStream msgInputStream,
			@Nullable final Pkcs12Config pkcs12Config, final ImportContext context,
            final org.simplejavamail.converter.internal.mimemessage.ProtectionScope enclosing, final OpenPgpReceiveConfig pgpKeys) {
		requireSmimeForExplicitDecryption(pkcs12Config);
		EmailFromOutlookMessage fromMsgBuilder = ModuleLoader.loadOutlookModule()
				.outlookMsgToEmailBuilder(msgInputStream, emailStartingBuilder, emailPopulatingBuilderFactory,
                        InternalEmailConverterImpl.forImport(context.traversal, this, pkcs12Config, enclosing, pgpKeys));
		return fromMsgBuilder;
	}

	private void requireSmimeForExplicitDecryption(@Nullable final Pkcs12Config pkcs12Config) {
		if (pkcs12Config != null) {
			// Explicit decryption is a requested feature, not the optional inspection attempted when its module happens to be present.
			smimeRuntime.module();
		}
	}

    OutlookEmailConversionResult toOutlookEmailConversionResult(final EmailFromOutlookMessage result,
            final Pkcs12Config pkcs12Config, final ImportContext context) {
        return new LogicalOutlookReader(this, emailStartingBuilder, smimeRuntime, pkcs12Config, null).read(result, context, null);
    }

    OutlookEmailConversionResult toOutlookEmailConversionResult(final EmailFromOutlookMessage result,
            final Pkcs12Config pkcs12Config, final ImportContext context,
            final org.simplejavamail.converter.internal.mimemessage.ProtectionScope enclosing, final OpenPgpReceiveConfig pgpKeys) {
        return new LogicalOutlookReader(this, emailStartingBuilder, smimeRuntime, pkcs12Config, pgpKeys).read(result, context, null, enclosing);
    }

	private static Session createDummySession() {
		return JakartaMailImplementation.createSession(new Properties());
	}

}
