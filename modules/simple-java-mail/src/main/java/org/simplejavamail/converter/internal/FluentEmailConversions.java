package org.simplejavamail.converter.internal;

import jakarta.mail.Session;
import jakarta.mail.internet.MimeMessage;
import java.io.IOException;
import java.util.Properties;
import java.util.List;
import java.util.function.Function;
import java.util.function.BiFunction;
import java.util.function.Supplier;
import lombok.experimental.UtilityClass;
import org.simplejavamail.api.converter.ConfiguredEmailConverter.EmailConversion;
import org.simplejavamail.api.converter.ConfiguredEmailConverter.EmlConversion;
import org.simplejavamail.api.converter.ConfiguredEmailConverter.IncomingConversion;
import org.simplejavamail.api.converter.ConfiguredEmailConverter.MimeMessageConversion;
import org.simplejavamail.api.converter.ConfiguredEmailConverter.OutlookConversion;
import org.simplejavamail.api.converter.EmailConversionResult;
import org.simplejavamail.api.converter.config.ImportLimitsConfig;
import org.simplejavamail.api.email.Email;
import org.simplejavamail.api.email.EmailPopulatingBuilder;
import org.simplejavamail.api.email.EmailStartingBuilder;
import org.simplejavamail.api.email.config.OpenPgpReceiveConfig;
import org.simplejavamail.api.mailer.config.EmailGovernance;
import org.simplejavamail.api.mailer.config.Pkcs12Config;
import org.simplejavamail.api.outlook.OutlookEmailConversionResult;
import org.simplejavamail.internal.util.JakartaMailImplementation;
import org.simplejavamail.mailer.internal.EmailGovernanceImpl;
import org.simplejavamail.internal.configresolution.ConfigurationChoice;
import org.simplejavamail.internal.configresolution.ConfigurationDeclaration;
import org.simplejavamail.internal.configresolution.ConfigurationOrigin;
import org.simplejavamail.internal.configresolution.ConfigurationResolution;
import org.simplejavamail.internal.configresolution.ConfigurationResolver;
import org.simplejavamail.internal.configresolution.ConfigurationSetting;

import static java.util.Objects.requireNonNull;

/** Single-use source conversions. Each terminal delegates the established path for its source and destination. */
@UtilityClass
class FluentEmailConversions {
    private static final ImportLimitsConfig NO_IMPORT_CHOICES = ImportLimitsConfig.builder().build();

	private abstract static class SingleConversion {
		private boolean consumed;

		final void checkAvailable() {
			if (consumed) {
				throw new IllegalStateException("This conversion has already been used. Choose the source again to start another conversion.");
			}
		}

		final void beginConversion() {
			checkAvailable();
			// Even a failed read consumes a stream. Consistent single-use semantics avoid returning a plausible result from a truncated retry.
			consumed = true;
		}
	}

	private abstract static class Incoming<T extends IncomingConversion<T>> extends SingleConversion implements IncomingConversion<T> {
		final ConfiguredEmailConverterImpl converter;
		private Pkcs12Config pkcs12Config;
		private OpenPgpReceiveConfig openPgpReceiveConfig;
		private boolean fetchAttachmentData = EmailStartingBuilder.DEFAULT_FETCH_ATTACHMENT_DATA;
		private boolean attachmentDataConfigured;
        private ImportLimitsConfig importLimits;

		Incoming(final ConfiguredEmailConverterImpl converter) {
			this.converter = converter;
		}

		abstract T self();

        @Override
        public final T withImportLimitsConfig(final ImportLimitsConfig limits) {
            checkAvailable();
            importLimits = requireNonNull(limits, "importLimitsConfig");
            return self();
        }

        @Override
        public final ImportLimitsConfig getImportLimitsConfig() { return importLimits == null ? NO_IMPORT_CHOICES : importLimits; }

        @Override
        public final T useConfiguredImportLimitsConfig() {
            checkAvailable();
            importLimits = null;
            return self();
        }

		abstract MimeMessage sourceMessage();

		MimeMessage sourceMessageForEmail() {
			return sourceMessage();
		}

        MimeMessage sourceMessageForEmail(final ImportContext context) { return sourceMessageForEmail(); }
        boolean hasRawSource() { return false; }

		/** @see IncomingConversion#withSmimeDecryption(Pkcs12Config) */
		@Override
		public final T withSmimeDecryption(final Pkcs12Config value) {
			checkAvailable();
			pkcs12Config = requireNonNull(value, "pkcs12Config");
			return self();
		}

		/** @see IncomingConversion#clearSmimeDecryption() */
		@Override
		public final T clearSmimeDecryption() {
			checkAvailable();
			pkcs12Config = null;
			return self();
		}

		/** @see IncomingConversion#withOpenPgpReceiveConfig(OpenPgpReceiveConfig) */
		@Override
		public final T withOpenPgpReceiveConfig(final OpenPgpReceiveConfig value) {
			checkAvailable();
			openPgpReceiveConfig = requireNonNull(value, "openPgpReceiveConfig");
			return self();
		}

		/** @see IncomingConversion#clearOpenPgpReceiveConfig() */
		@Override
		public final T clearOpenPgpReceiveConfig() {
			checkAvailable();
			openPgpReceiveConfig = null;
			return self();
		}

		/** @see IncomingConversion#withFetchAttachmentData(boolean) */
		@Override
		public final T withFetchAttachmentData(final boolean value) {
			checkAvailable();
			fetchAttachmentData = value;
			attachmentDataConfigured = true;
			return self();
		}

		/** @see IncomingConversion#resetFetchAttachmentData() */
		@Override
		public final T resetFetchAttachmentData() {
			checkAvailable();
			fetchAttachmentData = EmailStartingBuilder.DEFAULT_FETCH_ATTACHMENT_DATA;
			attachmentDataConfigured = false;
			return self();
		}

		final void checkDirectExportOptions() {
			checkAvailable();
			if (pkcs12Config != null || openPgpReceiveConfig != null || attachmentDataConfigured || importLimits != null) {
				throw new IllegalStateException("Receive-side options apply to toEmail() or toEmailBuilder(), not direct MIME/EML conversion. "
						+ "Choose an Email target, or clear the receive-side options before exporting the original message.");
			}
		}

		/** @see IncomingConversion#toEmailBuilder() */
		@Override
		public final EmailPopulatingBuilder toEmailBuilder() {
			final ImportContext context = converter.prepareImport(getImportLimitsConfig(), hasRawSource());
			beginConversion();
			final boolean fetchData = selectOptionOrCreateFallback(attachmentDataConfigured ? fetchAttachmentData : null,
					() -> EmailStartingBuilder.DEFAULT_FETCH_ATTACHMENT_DATA, "withFetchAttachmentData");
			try {
				try {
					return converter.mimeMessageToEmailBuilder(sourceMessageForEmail(context), pkcs12Config, openPgpReceiveConfig, fetchData, context);
				} catch (final SourceCloseException failure) {
					final EmailPopulatingBuilder builder = converter.mimeMessageToEmailBuilder(failure.getMimeMessage(),
							pkcs12Config, openPgpReceiveConfig, fetchData, context);
					ConfiguredEmailConverterImpl.noteInputCloseFailure(builder, (IOException) failure.getCause());
					return builder;
				}
			} catch (final SourceReadException failure) {
				return converter.sourceFailureBuilder(failure);
			}
		}

		/** @see IncomingConversion#toEmail() */
		@Override
		public final Email toEmail() {
			return toEmailBuilder().buildEmail();
		}
	}

	static final class FromEml extends Incoming<EmlConversion> implements EmlConversion {
		private final Function<Session, MimeMessage> source;
		private final BiFunction<Session, ImportContext, MimeMessage> preservedSource;
		private Session session;

		FromEml(final ConfiguredEmailConverterImpl converter, final Function<Session, MimeMessage> source,
				final BiFunction<Session, ImportContext, MimeMessage> preservedSource) {
			super(converter);
			this.source = source;
			this.preservedSource = preservedSource;
		}

		@Override
		EmlConversion self() {
			return this;
		}

		/** @see EmlConversion#withSession(Session) */
		@Override
		public EmlConversion withSession(final Session value) {
			checkAvailable();
			session = requireNonNull(value, "session");
			return this;
		}

		/** @see EmlConversion#resetSession() */
		@Override
		public EmlConversion resetSession() {
			checkAvailable();
			session = null;
			return this;
		}

		@Override
		MimeMessage sourceMessage() {
			return source.apply(selectOptionOrCreateFallback(session, FluentEmailConversions::emptySession, "withSession"));
		}

		@Override
		MimeMessage sourceMessageForEmail(final ImportContext context) {
			return preservedSource.apply(selectOptionOrCreateFallback(session, FluentEmailConversions::emptySession, "withSession"), context);
		}

        @Override boolean hasRawSource() { return true; }

		/** @see EmlConversion#toMimeMessage() */
		@Override
		public MimeMessage toMimeMessage() {
			return ArtifactConversions.value(toMimeMessageResult());
		}

		/** @see EmlConversion#toMimeMessageResult() */
		@Override
		public EmailConversionResult<MimeMessage> toMimeMessageResult() {
			checkDirectExportOptions();
			beginConversion();
			final ArtifactConversions.Operation operation = new ArtifactConversions.Operation(null);
			return operation.execute(() -> {
				try {
					final MimeMessage message = sourceMessage();
					operation.includeMime(message);
					return message;
				} catch (final SourceCloseException failure) {
					operation.includeMime(failure.getMimeMessage());
					operation.noteInputCloseFailure((IOException) failure.getCause());
					return failure.getMimeMessage();
				}
			});
		}
	}

	static final class FromMimeMessage extends Incoming<MimeMessageConversion> implements MimeMessageConversion {
		private final MimeMessage source;

		FromMimeMessage(final ConfiguredEmailConverterImpl converter, final MimeMessage source) {
			super(converter);
			this.source = source;
		}

		@Override
		MimeMessageConversion self() {
			return this;
		}

		@Override
		MimeMessage sourceMessage() {
			return source;
		}

		/** @see MimeMessageConversion#toEml() */
		@Override
		public String toEml() {
			return ArtifactConversions.value(toEmlResult());
		}

		/** @see MimeMessageConversion#toEmlResult() */
		@Override
		public EmailConversionResult<String> toEmlResult() {
			checkDirectExportOptions();
			beginConversion();
			final ArtifactConversions.Operation operation = new ArtifactConversions.Operation(null);
			// Raw passthrough does not request header/field acquisition merely to populate diagnostics.
			return operation.execute(() -> operation.writeText(source));
		}

		/** @see MimeMessageConversion#toEmlBytes() */
		@Override
		public byte[] toEmlBytes() {
			return ArtifactConversions.value(toEmlBytesResult());
		}

		/** @see MimeMessageConversion#toEmlBytesResult() */
		@Override
		public EmailConversionResult<byte[]> toEmlBytesResult() {
			checkDirectExportOptions();
			beginConversion();
			final ArtifactConversions.Operation operation = new ArtifactConversions.Operation(null);
			return operation.execute(() -> operation.write(source));
		}
	}

	private static class ExportOptions extends SingleConversion {
		final ConfiguredEmailConverterImpl converter;
		private Session session;
		private EmailGovernance governance;

		ExportOptions(final ConfiguredEmailConverterImpl converter) {
			this.converter = converter;
		}

		final void selectSession(final Session value) {
			checkAvailable();
			session = value;
		}

		final void selectGovernance(final EmailGovernance value) {
			checkAvailable();
			governance = value;
		}

		final MimeMessage exportEmail(final ArtifactConversions.Operation operation, final Email email, final boolean writingContent) {
			// Export preserves the existing no-template default; factory configuration belongs to imported builders, not implicit outgoing policy.
			return operation.render(email,
					selectOptionOrCreateFallback(session, FluentEmailConversions::emptySession, "withSession"),
					selectOptionOrCreateFallback(governance, EmailGovernanceImpl::NO_GOVERNANCE, "withEmailGovernance"),
					converter::copyForOutput, writingContent);
		}

		final void checkImportOptions() {
			checkAvailable();
			if (session != null || governance != null) {
				throw new IllegalStateException("Session and Email-governance options apply when exporting Outlook messages to MIME or EML. "
						+ "Choose an export target, or reset the Session and clear governance before importing the Email and source metadata.");
			}
		}
	}

	static final class FromEmail extends ExportOptions implements EmailConversion {
		private final Email source;

		FromEmail(final ConfiguredEmailConverterImpl converter, final Email source) {
			super(converter);
			this.source = source;
		}

		/** @see EmailConversion#withSession(Session) */
		@Override
		public EmailConversion withSession(final Session value) {
			selectSession(requireNonNull(value, "session"));
			return this;
		}

		/** @see EmailConversion#resetSession() */
		@Override
		public EmailConversion resetSession() {
			selectSession(null);
			return this;
		}

		/** @see EmailConversion#withEmailGovernance(EmailGovernance) */
		@Override
		public EmailConversion withEmailGovernance(final EmailGovernance value) {
			selectGovernance(requireNonNull(value, "emailGovernance"));
			return this;
		}

		/** @see EmailConversion#clearEmailGovernance() */
		@Override
		public EmailConversion clearEmailGovernance() {
			selectGovernance(null);
			return this;
		}

		/** @see EmailConversion#toMimeMessage() */
		@Override
		public MimeMessage toMimeMessage() {
			return ArtifactConversions.value(toMimeMessageResult());
		}

		/** @see EmailConversion#toMimeMessageResult() */
		@Override
		public EmailConversionResult<MimeMessage> toMimeMessageResult() {
			return convert(false, Function.identity());
		}

		/** @see EmailConversion#toEml() */
		@Override
		public String toEml() {
			return ArtifactConversions.value(toEmlResult());
		}

		/** @see EmailConversion#toEmlResult() */
		@Override
		public EmailConversionResult<String> toEmlResult() {
			return convert(true, (operation, message) -> operation.writeText(message));
		}

		/** @see EmailConversion#toEmlBytes() */
		@Override
		public byte[] toEmlBytes() {
			return ArtifactConversions.value(toEmlBytesResult());
		}

		/** @see EmailConversion#toEmlBytesResult() */
		@Override
		public EmailConversionResult<byte[]> toEmlBytesResult() {
			return convert(true, (operation, message) -> operation.write(message));
		}

		private <T> EmailConversionResult<T> convert(final boolean writingContent, final Function<MimeMessage, T> target) {
			return convert(writingContent, (operation, message) -> target.apply(message));
		}

		private <T> EmailConversionResult<T> convert(final boolean writingContent,
				final java.util.function.BiFunction<ArtifactConversions.Operation, MimeMessage, T> target) {
			beginConversion();
			final ArtifactConversions.Operation operation = new ArtifactConversions.Operation(source.getId());
			operation.includeEmail(source, false);
			return operation.execute(() -> target.apply(operation, exportEmail(operation, source, writingContent)));
		}
	}

	static final class FromOutlook extends ExportOptions implements OutlookConversion {
		private final BiFunction<Pkcs12Config, ImportContext, OutlookEmailConversionResult> source;
		private Pkcs12Config pkcs12Config;
        private ImportLimitsConfig importLimits;

		FromOutlook(final ConfiguredEmailConverterImpl converter, final BiFunction<Pkcs12Config, ImportContext, OutlookEmailConversionResult> source) {
			super(converter);
			this.source = source;
		}

        @Override
        public OutlookConversion withImportLimitsConfig(final ImportLimitsConfig limits) {
            checkAvailable();
            importLimits = requireNonNull(limits, "importLimitsConfig");
            return this;
        }

        @Override
        public ImportLimitsConfig getImportLimitsConfig() { return importLimits == null ? NO_IMPORT_CHOICES : importLimits; }

        @Override
        public OutlookConversion useConfiguredImportLimitsConfig() {
            checkAvailable();
            importLimits = null;
            return this;
        }

		/** @see OutlookConversion#withSmimeDecryption(Pkcs12Config) */
		@Override
		public OutlookConversion withSmimeDecryption(final Pkcs12Config value) {
			checkAvailable();
			pkcs12Config = requireNonNull(value, "pkcs12Config");
			return this;
		}

		/** @see OutlookConversion#clearSmimeDecryption() */
		@Override
		public OutlookConversion clearSmimeDecryption() {
			checkAvailable();
			pkcs12Config = null;
			return this;
		}

		/** @see OutlookConversion#withSession(Session) */
		@Override
		public OutlookConversion withSession(final Session value) {
			selectSession(requireNonNull(value, "session"));
			return this;
		}

		/** @see OutlookConversion#resetSession() */
		@Override
		public OutlookConversion resetSession() {
			selectSession(null);
			return this;
		}

		/** @see OutlookConversion#withEmailGovernance(EmailGovernance) */
		@Override
		public OutlookConversion withEmailGovernance(final EmailGovernance value) {
			selectGovernance(requireNonNull(value, "emailGovernance"));
			return this;
		}

		/** @see OutlookConversion#clearEmailGovernance() */
		@Override
		public OutlookConversion clearEmailGovernance() {
			selectGovernance(null);
			return this;
		}

		/** @see OutlookConversion#toEmailBuilderWithOutlookData() */
		@Override
		public OutlookEmailConversionResult toEmailBuilderWithOutlookData() {
			checkImportOptions();
			return readSource();
		}

		private OutlookEmailConversionResult readSource() {
            final ImportContext context = converter.prepareImport(getImportLimitsConfig(), true);
			beginConversion();
			return source.apply(pkcs12Config, context);
		}

		/** @see OutlookConversion#toEmailBuilder() */
		@Override
		public EmailPopulatingBuilder toEmailBuilder() {
			return toEmailBuilderWithOutlookData().getEmailBuilder();
		}

		/** @see OutlookConversion#toEmail() */
		@Override
		public Email toEmail() {
			return toEmailBuilder().buildEmail();
		}

		/** @see OutlookConversion#toMimeMessage() */
		@Override
		public MimeMessage toMimeMessage() {
			return ArtifactConversions.value(toMimeMessageResult());
		}

		/** @see OutlookConversion#toMimeMessageResult() */
		@Override
		public EmailConversionResult<MimeMessage> toMimeMessageResult() {
			return convert(false, (operation, message) -> message);
		}

		/** @see OutlookConversion#toEml() */
		@Override
		public String toEml() {
			return ArtifactConversions.value(toEmlResult());
		}

		/** @see OutlookConversion#toEmlResult() */
		@Override
		public EmailConversionResult<String> toEmlResult() {
			return convert(true, (operation, message) -> operation.writeText(message));
		}

		/** @see OutlookConversion#toEmlBytes() */
		@Override
		public byte[] toEmlBytes() {
			return ArtifactConversions.value(toEmlBytesResult());
		}

		/** @see OutlookConversion#toEmlBytesResult() */
		@Override
		public EmailConversionResult<byte[]> toEmlBytesResult() {
			return convert(true, (operation, message) -> operation.write(message));
		}

		private <T> EmailConversionResult<T> convert(final boolean writingContent,
				final java.util.function.BiFunction<ArtifactConversions.Operation, MimeMessage, T> target) {
			final ArtifactConversions.Operation operation = new ArtifactConversions.Operation(null);
			return operation.execute(() -> {
				final OutlookEmailConversionResult imported = readSource();
				final Email email = ConfiguredEmailConverterImpl.buildOutlookArtifactInput(imported);
				operation.includeEmail(email, true);
				return target.apply(operation, exportEmail(operation, email, writingContent));
			});
		}
	}

	/** Select before creating a fallback Session or governance; explicit borrowed inputs must not initialize unused resources. */
	private static <T> T selectOptionOrCreateFallback(final T supplied, final Supplier<T> fallback, final String setter) {
		final ConfigurationSetting<T> setting = ConfigurationSetting.of("conversion", setter);
		final ConfigurationResolution<T> selected = ConfigurationResolver.resolve(setting, List.of(
				ConfigurationDeclaration.javaChoice(setting, ConfigurationChoice.ofNullable(supplied), setter),
				ConfigurationDeclaration.of(setting, ConfigurationChoice.remove(),
						ConfigurationOrigin.of(ConfigurationOrigin.Kind.BUILT_IN, setter, "conversion fallback"))),
				List.of(ConfigurationOrigin.Kind.JAVA, ConfigurationOrigin.Kind.BUILT_IN));
		return selected.getSelected().getOrigin().getKind() == ConfigurationOrigin.Kind.JAVA ? selected.valueOrNull() : fallback.get();
	}

	private static Session emptySession() {
		return JakartaMailImplementation.createSession(new Properties());
	}
}
