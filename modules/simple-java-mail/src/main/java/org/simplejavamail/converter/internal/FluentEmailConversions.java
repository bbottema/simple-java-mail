package org.simplejavamail.converter.internal;

import jakarta.mail.Session;
import jakarta.mail.internet.MimeMessage;
import java.util.Properties;
import java.util.List;
import java.util.function.Function;
import java.util.function.Supplier;
import lombok.experimental.UtilityClass;
import org.simplejavamail.api.converter.ConfiguredEmailConverter.EmailConversion;
import org.simplejavamail.api.converter.ConfiguredEmailConverter.EmlConversion;
import org.simplejavamail.api.converter.ConfiguredEmailConverter.IncomingConversion;
import org.simplejavamail.api.converter.ConfiguredEmailConverter.MimeMessageConversion;
import org.simplejavamail.api.converter.ConfiguredEmailConverter.OutlookConversion;
import org.simplejavamail.api.email.Email;
import org.simplejavamail.api.email.EmailPopulatingBuilder;
import org.simplejavamail.api.email.EmailStartingBuilder;
import org.simplejavamail.api.email.config.OpenPgpReceiveConfig;
import org.simplejavamail.api.mailer.config.EmailGovernance;
import org.simplejavamail.api.mailer.config.Pkcs12Config;
import org.simplejavamail.api.outlook.OutlookEmailConversionResult;
import org.simplejavamail.converter.EmailConverter;
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

		Incoming(final ConfiguredEmailConverterImpl converter) {
			this.converter = converter;
		}

		abstract T self();

		abstract MimeMessage sourceMessage();

		MimeMessage sourceMessageForEmail() {
			return sourceMessage();
		}

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
			if (pkcs12Config != null || openPgpReceiveConfig != null || attachmentDataConfigured) {
				throw new IllegalStateException("Receive-side options apply to toEmail() or toEmailBuilder(), not direct MIME/EML conversion. "
						+ "Choose an Email target, or clear the receive-side options before exporting the original message.");
			}
		}

		/** @see IncomingConversion#toEmailBuilder() */
		@Override
		public final EmailPopulatingBuilder toEmailBuilder() {
			beginConversion();
			final boolean fetchData = selectOptionOrCreateFallback(attachmentDataConfigured ? fetchAttachmentData : null,
					() -> EmailStartingBuilder.DEFAULT_FETCH_ATTACHMENT_DATA, "withFetchAttachmentData");
			return converter.mimeMessageToEmailBuilder(sourceMessageForEmail(), pkcs12Config, openPgpReceiveConfig, fetchData);
		}

		/** @see IncomingConversion#toEmail() */
		@Override
		public final Email toEmail() {
			return toEmailBuilder().buildEmail();
		}
	}

	static final class FromEml extends Incoming<EmlConversion> implements EmlConversion {
		private final Function<Session, MimeMessage> source;
		private final Function<Session, MimeMessage> preservedSource;
		private Session session;

		FromEml(final ConfiguredEmailConverterImpl converter, final Function<Session, MimeMessage> source,
				final Function<Session, MimeMessage> preservedSource) {
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
		MimeMessage sourceMessageForEmail() {
			return preservedSource.apply(selectOptionOrCreateFallback(session, FluentEmailConversions::emptySession, "withSession"));
		}

		/** @see EmlConversion#toMimeMessage() */
		@Override
		public MimeMessage toMimeMessage() {
			checkDirectExportOptions();
			beginConversion();
			return sourceMessage();
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
			checkDirectExportOptions();
			beginConversion();
			return EmailConverter.mimeMessageToEML(source);
		}

		/** @see MimeMessageConversion#toEmlBytes() */
		@Override
		public byte[] toEmlBytes() {
			checkDirectExportOptions();
			beginConversion();
			return EmailConverter.mimeMessageToEMLByteArray(source);
		}
	}

	private static class ExportOptions extends SingleConversion {
		private Session session;
		private EmailGovernance governance;

		final void selectSession(final Session value) {
			checkAvailable();
			session = value;
		}

		final void selectGovernance(final EmailGovernance value) {
			checkAvailable();
			governance = value;
		}

		final MimeMessage exportEmail(final Email email) {
			// Export preserves the existing no-template default; factory configuration belongs to imported builders, not implicit outgoing policy.
			return EmailConverter.emailToMimeMessage(email,
					selectOptionOrCreateFallback(session, FluentEmailConversions::emptySession, "withSession"),
					selectOptionOrCreateFallback(governance, EmailGovernanceImpl::NO_GOVERNANCE, "withEmailGovernance"));
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

		FromEmail(final Email source) {
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
			beginConversion();
			return exportEmail(source);
		}

		/** @see EmailConversion#toEml() */
		@Override
		public String toEml() {
			return EmailConverter.mimeMessageToEML(toMimeMessage());
		}

		/** @see EmailConversion#toEmlBytes() */
		@Override
		public byte[] toEmlBytes() {
			return EmailConverter.mimeMessageToEMLByteArray(toMimeMessage());
		}
	}

	static final class FromOutlook extends ExportOptions implements OutlookConversion {
		private final Function<Pkcs12Config, OutlookEmailConversionResult> source;
		private Pkcs12Config pkcs12Config;

		FromOutlook(final Function<Pkcs12Config, OutlookEmailConversionResult> source) {
			this.source = source;
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
			beginConversion();
			return source.apply(pkcs12Config);
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
			return exportEmail(readSource().buildEmail());
		}

		/** @see OutlookConversion#toEml() */
		@Override
		public String toEml() {
			return EmailConverter.mimeMessageToEML(toMimeMessage());
		}

		/** @see OutlookConversion#toEmlBytes() */
		@Override
		public byte[] toEmlBytes() {
			return EmailConverter.mimeMessageToEMLByteArray(toMimeMessage());
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
