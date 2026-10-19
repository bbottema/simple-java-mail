package org.simplejavamail.api.converter;

import jakarta.mail.Session;
import jakarta.mail.internet.MimeMessage;
import org.jetbrains.annotations.NotNull;
import org.simplejavamail.api.email.Email;
import org.simplejavamail.api.email.EmailPopulatingBuilder;
import org.simplejavamail.api.email.EmailStartingBuilder;
import org.simplejavamail.api.email.config.OpenPgpReceiveConfig;
import org.simplejavamail.api.mailer.config.EmailGovernance;
import org.simplejavamail.api.mailer.config.Pkcs12Config;
import org.simplejavamail.api.outlook.OutlookEmailConversionResult;
import org.simplejavamail.config.SimpleJavaMailConfig;

import java.io.File;
import java.io.InputStream;
import java.nio.file.Path;

/**
 * Choose an input format, then choose what to convert it to. For example, {@code converter.fromEml(eml).toEmail()} reads an EML message as an Email.
 * Imported Email builders retain this factory's {@link SimpleJavaMailConfig} snapshot, including builders for nested messages.
 * <p>
 * Selecting a source does not read it. Configure the returned conversion, then call one terminal {@code to...} method. Each conversion is single-use,
 * once reading or conversion starts, even if it fails; start another conversion to obtain another result. Incompatible option/target choices are
 * rejected before reading, so they can still be corrected. Conversion options are mutable and not thread-safe.
 * In the fluent {@code from...} API, caller-supplied streams remain open. Streams opened for a File or Path are closed before the terminal method returns.
 * <p>
 * Direct MIME/EML conversions do not round-trip through Email. Receive-only options are rejected for those direct targets rather than silently ignored.
 * Exporting an Email does not apply factory templates unless an explicit
 * {@link EmailConversion#withEmailGovernance(EmailGovernance) governance} is supplied.
 * <p>
 * EML imports retain the original protected representation, including tolerated header line endings. When required message content cannot be read
 * or written, conversion fails with a {@link org.simplejavamail.MailException} that retains the original cause. Keep borrowed attachment sources
 * available until conversion finishes.
 *
 * @see EmailPopulatingBuilder
 */
public interface ConfiguredEmailConverter {

	/** Converts the supplied Email without changing it. */
	@NotNull EmailConversion fromEmail(@NotNull Email email);

	/** Reads a Jakarta Mail message, or exports that message directly without converting through Email. */
	@NotNull MimeMessageConversion fromMimeMessage(@NotNull MimeMessage message);

	/** Reads EML text as UTF-8. This parameter is the message content, not a file name. */
	@NotNull EmlConversion fromEml(@NotNull String eml);

	/** Reads EML bytes. The conversion retains its own copy so subsequent array changes cannot alter its input. */
	@NotNull EmlConversion fromEml(@NotNull byte[] eml);

	/** Reads an EML file when a terminal conversion is requested, then closes its stream. */
	@NotNull EmlConversion fromEml(@NotNull File emlFile);

	/** Reads an EML path on its own filesystem when a terminal conversion is requested, then closes its stream. */
	@NotNull EmlConversion fromEml(@NotNull Path emlPath);

	/** Reads the supplied EML stream once, without closing it. */
	@NotNull EmlConversion fromEml(@NotNull InputStream emlInputStream);

	/** Reads the Outlook MSG file at this file name. Outlook conversion requires the optional Outlook module. */
	@NotNull OutlookConversion fromOutlookMsg(@NotNull String msgFileName);

	/** Reads an Outlook MSG file when a terminal conversion is requested, then closes its stream. */
	@NotNull OutlookConversion fromOutlookMsg(@NotNull File msgFile);

	/** Reads an Outlook MSG path on its own filesystem when a terminal conversion is requested, then closes its stream. */
	@NotNull OutlookConversion fromOutlookMsg(@NotNull Path msgPath);

	/** Reads the supplied Outlook MSG stream once, without closing it. */
	@NotNull OutlookConversion fromOutlookMsg(@NotNull InputStream msgInputStream);

	/** Receive-side options shared by EML and MIME imports. Options affect Email conversion, not direct MIME/EML export. */
	interface IncomingConversion<T extends IncomingConversion<T>> {

		/** Supplies the private key store for reading S/MIME-encrypted content. Repeated calls replace the previous configuration. */
		@NotNull T withSmimeDecryption(@NotNull Pkcs12Config pkcs12Config);

		/** @see #withSmimeDecryption(Pkcs12Config) */
		@NotNull T clearSmimeDecryption();

		/** Supplies keys and verification settings for reading OpenPGP/MIME content. Repeated calls replace the previous configuration. */
		@NotNull T withOpenPgpReceiveConfig(@NotNull OpenPgpReceiveConfig openPgpReceiveConfig);

		/** @see #withOpenPgpReceiveConfig(OpenPgpReceiveConfig) */
		@NotNull T clearOpenPgpReceiveConfig();

		/**
		 * Chooses whether Email conversion reads attachment data. The default is {@value EmailStartingBuilder#DEFAULT_FETCH_ATTACHMENT_DATA}.
		 * When disabled, attachment data sources are retained for later access instead of reading their content eagerly. Reading those data sources later
		 * can still perform I/O; this does not remove attachments or prohibit sending them. This option controls attachment extraction, not protection
		 * inspection: a protection module may still need to read or serialize the source while examining it.
		 */
		@NotNull T withFetchAttachmentData(boolean fetchAttachmentData);

		/** @see #withFetchAttachmentData(boolean) */
		@NotNull T resetFetchAttachmentData();

		/** Reads the source into an editable builder retaining this factory's configuration. */
		@NotNull EmailPopulatingBuilder toEmailBuilder();

		/** Reads the source into an Email, equivalent to {@code toEmailBuilder().buildEmail()}. */
		@NotNull Email toEmail();
	}

	/** Converts EML input directly to MIME, or interprets it as an Email. */
	interface EmlConversion extends IncomingConversion<EmlConversion> {

		/** Uses this Session for parsing; the conversion does not own or close it. Otherwise a temporary empty Session is used. */
		@NotNull EmlConversion withSession(@NotNull Session session);

		/** @see #withSession(Session) */
		@NotNull EmlConversion resetSession();

		/** Parses the original message directly. Receive-side decryption and attachment options must be cleared before choosing this target. */
		@NotNull MimeMessage toMimeMessage();
	}

	/** Converts a MIME message into Email, or writes its EML representation directly. */
	interface MimeMessageConversion extends IncomingConversion<MimeMessageConversion> {

		/** Writes the supplied MIME message as UTF-8 EML text. Receive-side options must be cleared before choosing this direct-export target. */
		@NotNull String toEml();

		/** Writes the supplied MIME message to EML bytes. Receive-side options must be cleared before choosing this direct-export target. */
		@NotNull byte[] toEmlBytes();
	}

	/** Converts Email to MIME or EML. Exact/protected-message rules continue to apply. */
	interface EmailConversion {

		/** Uses this Session for MIME conversion; the conversion does not own or close it. Otherwise a temporary empty Session is used. */
		@NotNull EmailConversion withSession(@NotNull Session session);

		/** @see #withSession(Session) */
		@NotNull EmailConversion resetSession();

		/**
		 * Applies this supplied governance during conversion, following the same rules as sending. Without it, no defaults or overrides are added.
		 * Repeated calls replace the previous governance. Exact and protected content retain their established compatibility checks.
		 */
		@NotNull EmailConversion withEmailGovernance(@NotNull EmailGovernance emailGovernance);

		/** @see #withEmailGovernance(EmailGovernance) */
		@NotNull EmailConversion clearEmailGovernance();

		/** Produces a MIME message using the selected Session and optional governance. */
		@NotNull MimeMessage toMimeMessage();

		/** Produces the MIME message's UTF-8 EML text. Prefer bytes when preserving arbitrary original content. */
		@NotNull String toEml();

		/** Produces EML bytes. Exact EML retains its authoritative bytes. */
		@NotNull byte[] toEmlBytes();
	}

	/**
	 * Converts an Outlook MSG source and can retain its Outlook-specific metadata. Outlook imports deliberately suppress ordinary Email defaults,
	 * including when supplied governance is used for export; overrides still follow their usual rules. To opt into defaults, obtain the Email builder
	 * with {@link #toEmailBuilder()}, call {@link EmailPopulatingBuilder#ignoringDefaults(boolean) ignoringDefaults(false)}, then complete or export
	 * the resulting Email.
	 */
	interface OutlookConversion {

		/** Supplies the private key store for reading S/MIME-encrypted attachments. Repeated calls replace the previous configuration. */
		@NotNull OutlookConversion withSmimeDecryption(@NotNull Pkcs12Config pkcs12Config);

		/** @see #withSmimeDecryption(Pkcs12Config) */
		@NotNull OutlookConversion clearSmimeDecryption();

		/** Uses this Session when producing MIME or EML. It is not used to parse the Outlook container and remains caller-owned. */
		@NotNull OutlookConversion withSession(@NotNull Session session);

		/** @see #withSession(Session) */
		@NotNull OutlookConversion resetSession();

		/**
		 * Applies supplied governance when producing MIME or EML. It does not alter the source metadata or the imported Email builder.
		 * Without it, no defaults or overrides are added during export.
		 */
		@NotNull OutlookConversion withEmailGovernance(@NotNull EmailGovernance emailGovernance);

		/** @see #withEmailGovernance(EmailGovernance) */
		@NotNull OutlookConversion clearEmailGovernance();

		/** Reads the source into an Email builder. Export-only Session and governance options must be reset before choosing this target. */
		@NotNull EmailPopulatingBuilder toEmailBuilder();

		/** Reads the source into an Email. Export-only Session and governance options must be reset before choosing this target. */
		@NotNull Email toEmail();

		/** Reads the source and its Outlook metadata. Export-only Session and governance options must be reset before choosing this target. */
		@NotNull OutlookEmailConversionResult toEmailBuilderWithOutlookData();

		/** Converts the Outlook source to Email, then produces MIME using the selected Session and governance. */
		@NotNull MimeMessage toMimeMessage();

		/** Converts the Outlook source to Email, then produces UTF-8 EML text. */
		@NotNull String toEml();

		/** Converts the Outlook source to Email, then produces EML bytes. */
		@NotNull byte[] toEmlBytes();
	}

}
