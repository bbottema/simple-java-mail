package org.simplejavamail.email.internal;

import org.simplejavamail.config.ConfigKeys;

import static org.simplejavamail.internal.config.ConfigurationSnapshot.locksOf;

import jakarta.activation.DataSource;
import jakarta.mail.Message.RecipientType;
import jakarta.mail.internet.InternetAddress;
import jakarta.mail.internet.MimeMessage;
import lombok.val;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.simplejavamail.api.email.AttachmentResource;
import org.simplejavamail.api.email.CalendarMethod;
import org.simplejavamail.api.email.ContentTransferEncoding;
import org.simplejavamail.api.email.config.ImageResolutionConfig;
import org.simplejavamail.api.email.config.ContentEncodingConfig;
import org.simplejavamail.api.email.config.EmailTemplateOptions;
import org.simplejavamail.api.email.config.SmtpDeliveryOptions;
import org.simplejavamail.api.email.Email;
import org.simplejavamail.api.email.EmailPopulatingBuilder;
import org.simplejavamail.api.email.EmailStartingBuilder;
import org.simplejavamail.api.email.OriginalSmimeDetails;
import org.simplejavamail.api.email.DeliveryStatusReport;
import org.simplejavamail.internal.email.OpenPgpDetailsImpl;
import org.simplejavamail.api.email.OriginalOpenPgpDetails;
import org.simplejavamail.api.email.Recipient;
import org.simplejavamail.api.email.config.DkimConfig;
import org.simplejavamail.api.email.config.DeliveryStatusNotification;
import org.simplejavamail.api.email.config.DeliveryStatusNotification.DeliveryStatusNotificationBuilder;
import org.simplejavamail.api.email.config.OpenPgpEncryptionConfig;
import org.simplejavamail.api.email.config.OpenPgpSigningConfig;
import org.simplejavamail.api.email.config.SmimeEncryptionConfig;
import org.simplejavamail.api.email.config.SmimeSigningConfig;
import org.simplejavamail.api.internal.clisupport.model.Cli;
import org.simplejavamail.api.internal.smimesupport.model.PlainSmimeDetails;
import org.simplejavamail.api.mailer.config.EmailGovernance;
import org.simplejavamail.config.SimpleJavaMailConfig;
import org.simplejavamail.api.email.EmailProperty;
import org.simplejavamail.internal.moduleloader.ModuleLoader;
import org.simplejavamail.internal.util.FileUtil;
import org.simplejavamail.internal.util.MiscUtil;
import org.simplejavamail.internal.util.NamedDataSource;
import org.simplejavamail.mailer.internal.EmailGovernanceImpl;
import org.simplejavamail.recipient.internal.MailboxInterpretation;

import java.io.File;
import java.io.IOException;
import java.net.MalformedURLException;
import java.net.URL;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Date;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.regex.Matcher;

import static java.lang.String.format;
import static java.util.Arrays.asList;
import static java.util.Collections.emptySet;
import static java.util.Objects.requireNonNull;
import static java.util.regex.Matcher.quoteReplacement;
import static org.simplejavamail.config.ConfigLoader.Property.EMBEDDEDIMAGES_DYNAMICRESOLUTION_BASE_CLASSPATH;
import static org.simplejavamail.config.ConfigLoader.Property.EMBEDDEDIMAGES_DYNAMICRESOLUTION_BASE_DIR;
import static org.simplejavamail.config.ConfigLoader.Property.EMBEDDEDIMAGES_DYNAMICRESOLUTION_BASE_URL;
import static org.simplejavamail.config.ConfigLoader.Property.EMBEDDEDIMAGES_DYNAMICRESOLUTION_ENABLE_CLASSPATH;
import static org.simplejavamail.config.ConfigLoader.Property.EMBEDDEDIMAGES_DYNAMICRESOLUTION_ENABLE_DIR;
import static org.simplejavamail.config.ConfigLoader.Property.EMBEDDEDIMAGES_DYNAMICRESOLUTION_ENABLE_URL;
import static org.simplejavamail.config.ConfigLoader.Property.EMBEDDEDIMAGES_DYNAMICRESOLUTION_MUSTBESUCCESFUL;
import static org.simplejavamail.config.ConfigLoader.Property.EMBEDDEDIMAGES_DYNAMICRESOLUTION_OUTSIDE_BASE_CLASSPATH;
import static org.simplejavamail.config.ConfigLoader.Property.EMBEDDEDIMAGES_DYNAMICRESOLUTION_OUTSIDE_BASE_DIR;
import static org.simplejavamail.config.ConfigLoader.Property.EMBEDDEDIMAGES_DYNAMICRESOLUTION_OUTSIDE_BASE_URL;
import static org.simplejavamail.email.internal.EmailException.ERROR_PARSING_URL;
import static org.simplejavamail.email.internal.EmailException.ERROR_READING_FROM_FILE;
import static org.simplejavamail.email.internal.EmailException.ERROR_RESOLVING_IMAGE_DATASOURCE;
import static org.simplejavamail.email.internal.EmailException.NAME_MISSING_FOR_EMBEDDED_IMAGE;
import static org.simplejavamail.internal.util.MiscUtil.defaultTo;
import static org.simplejavamail.internal.util.MiscUtil.randomCid10;
import static org.simplejavamail.internal.util.MiscUtil.tryResolveFileDataSourceFromClassPath;
import static org.simplejavamail.internal.util.MiscUtil.tryResolveImageFileDataSourceFromDisk;
import static org.simplejavamail.internal.util.MiscUtil.tryResolveUrlDataSource;
import static org.simplejavamail.internal.util.MiscUtil.valueNullOrEmpty;
import static org.simplejavamail.internal.util.Preconditions.checkNonEmptyArgument;
import static org.simplejavamail.internal.util.Preconditions.verifyNonnullOrEmpty;

/**
 * @see EmailPopulatingBuilder
 */
@SuppressWarnings({"UnusedReturnValue", "unused"})
public class EmailPopulatingBuilderImpl implements InternalEmailPopulatingBuilder {
	@NotNull
	private final SimpleJavaMailConfig config;
	@NotNull private ImageResolutionConfig imageResolutionConfig = ImageResolutionConfig.builder().build();

	/**
	 * @see #ignoringDefaults(boolean)
	 */
	private boolean ignoringDefaults;

	/**
	 * @see #ignoringOverrides(boolean)
	 */
	private boolean ignoringOverrides;

	@Nullable
	private Set<EmailProperty> propertiesNotToApplyDefaultValueFor;

	@Nullable
	private Set<EmailProperty> propertiesNotToApplyOverrideValueFor;

	/**
	 * @see #fixingMessageId(String)
	 */
	@Nullable
	private String id;

	/**
	 * @see #from(Recipient)
	 */
	private Recipient fromRecipient;

	/**
	 * @see #withReplyTo(Recipient)
	 */
	@NotNull
	private final List<Recipient> replyToRecipients = new ArrayList<>();

	/**
	 * @see #withBounceTo(Recipient)
	 */
	@Nullable
	private Recipient bounceToRecipient;

	/**
	 * @see #withDeliveryStatusNotification(DeliveryStatusNotification)
	 */
	@Nullable
	private DeliveryStatusNotification deliveryStatusNotification;
	private boolean tlsRequiredForOnwardDelivery;
	/** @see org.simplejavamail.api.email.config.SmtpDeliveryOptions.Builder#withSendingToAcceptedRecipients(boolean) */
	@Nullable
	private Boolean sendingToAcceptedRecipients;

	/**
	 * @see #withSubject(String)
	 */
	@Nullable
	private String subject;

	/**
	 * @see #withPlainText(String)
	 */
	@Nullable
	private String text;

	/**
	 * @see #withHTMLText(String)
	 */
	@Nullable
	private String textHTML;

	/**
	 * @see #withCalendarText(CalendarMethod, String)
	 */
	@Nullable
	private CalendarMethod calendarMethod;

	/**
	 * @see #withCalendarText(CalendarMethod, String)
	 */
	@Nullable
	private String textCalendar;

	/**
	 * @see #withContentTransferEncoding(ContentTransferEncoding)
	 */
	@Nullable
	private ContentTransferEncoding contentTransferEncoding;

	/**
	 * @see #withPlainTextContentTransferEncoding(ContentTransferEncoding)
	 */
	@Nullable
	private ContentTransferEncoding plainTextContentTransferEncoding;

	/**
	 * @see #withHTMLTextContentTransferEncoding(ContentTransferEncoding)
	 */
	@Nullable
	private ContentTransferEncoding htmlTextContentTransferEncoding;

	/**
	 * @see #withCalendarTextContentTransferEncoding(ContentTransferEncoding)
	 */
	@Nullable
	private ContentTransferEncoding calendarTextContentTransferEncoding;

	/**
	 * @see #withRecipients(Recipient...)
	 * @see #withRecipients(Collection)
	 */
	@NotNull
	private final List<Recipient> recipients = new ArrayList<>();

	/**
	 * @see #withEmbeddedImageAutoResolutionForFiles(boolean)
	 */
	private boolean embeddedImageAutoResolutionForFiles;

	/**
	 * @see #withEmbeddedImageAutoResolutionForClassPathResources(boolean)
	 */
	private boolean embeddedImageAutoResolutionForClassPathResources;

	/**
	 * @see #withEmbeddedImageAutoResolutionForURLs(boolean)
	 */
	private boolean embeddedImageAutoResolutionForURLs;

	/**
	 * @see #withEmbeddedImageBaseDir(String)
	 */
	@Nullable
	private String embeddedImageBaseDir;

	/**
	 * @see #withEmbeddedImageBaseClassPath(String)
	 */
	@Nullable
	private String embeddedImageBaseClassPath;

	/**
	 * @see #withEmbeddedImageBaseUrl(URL)
	 */
	@Nullable
	private URL embeddedImageBaseUrl;

	/**
	 * @see #allowingEmbeddedImageOutsideBaseDir(boolean)
	 */
	private boolean allowEmbeddedImageOutsideBaseDir;

	/**
	 * @see #allowingEmbeddedImageOutsideBaseClassPath(boolean)
	 */
	private boolean allowEmbeddedImageOutsideBaseClassPath;

	/**
	 * @see #allowingEmbeddedImageOutsideBaseUrl(boolean)
	 */
	private boolean allowEmbeddedImageOutsideBaseUrl;

	/**
	 * @see #embeddedImageAutoResolutionMustBeSuccesful(boolean)
	 */
	private boolean embeddedImageAutoResolutionMustBeSuccesful;

	/**
	 * @see #withEmbeddedImage(String, DataSource)
	 */
	@NotNull
	private final List<AttachmentResource> embeddedImages = new ArrayList<>();

	/**
	 * @see #withAttachment(String, DataSource)
	 */
	@NotNull
	private final List<AttachmentResource> attachments = new ArrayList<>();

	/**
	 * @see #withDecryptedAttachments(List)
	 */
	@NotNull
	private final List<AttachmentResource> decryptedAttachments = new ArrayList<>();

	/**
	 * @see #withHeader(String, Object)
	 * @see EmailStartingBuilder#replyingTo(MimeMessage, boolean, String)
	 */
	@NotNull
	// Header names identify the same field regardless of capitalization; retain the first supplied spelling for display.
	private final Map<String, Collection<String>> headers = new TreeMap<>(String.CASE_INSENSITIVE_ORDER);

	/**
	 * @see #signWithDomainKey(DkimConfig)
	 * @see org.simplejavamail.api.email.config.DkimConfig.DkimConfigBuilder#dkimPrivateKeyData(byte[])
	 */
	@Nullable
	private DkimConfig dkimConfig;

	/**
	 * @see #signWithSmime(SmimeSigningConfig)
	 * @see org.simplejavamail.api.email.config.SmimeSigningConfig.SmimeSigningConfigBuilder#pkcs12Config(File, String, String, String)
	 * @see #encryptWithSmime(SmimeEncryptionConfig)
	 * @see org.simplejavamail.api.email.config.SmimeEncryptionConfig.SmimeEncryptionConfigBuilder#x509Certificate(File)
	 */
	@Nullable
	private SmimeSigningConfig smimeSigningConfig;

	/**
	 * @see #encryptWithSmime(SmimeEncryptionConfig)
	 * @see org.simplejavamail.api.email.config.SmimeEncryptionConfig.SmimeEncryptionConfigBuilder#x509Certificate(File)
	 * @see #signWithSmime(SmimeSigningConfig)
	 * @see org.simplejavamail.api.email.config.SmimeSigningConfig.SmimeSigningConfigBuilder#pkcs12Config(File, String, String, String)
	 */
	@Nullable
	private SmimeEncryptionConfig smimeEncryptionConfig;

	@Nullable
	private OpenPgpSigningConfig openPgpSigningConfig;

	@Nullable
	private OpenPgpEncryptionConfig openPgpEncryptionConfig;

	/**
	 * @see #withDispositionNotificationTo()
	 * @see #withDispositionNotificationTo(Recipient)
	 */
	@Nullable
	private Boolean useDispositionNotificationTo;

	/**
	 * @see #withDispositionNotificationTo()
	 * @see #withDispositionNotificationTo(Recipient)
	 */
	@Nullable
	private List<Recipient> dispositionNotificationToRecipients = new ArrayList<>();

	/**
	 * @see #withReturnReceiptTo()
	 * @see #withReturnReceiptTo(Recipient)
	 */
	@Nullable
	private Boolean useReturnReceiptTo;

	/**
	 * @see #withReturnReceiptTo()
	 * @see #withReturnReceiptTo(Recipient)
	 */
	@Nullable
	private List<Recipient> returnReceiptToRecipients = new ArrayList<>();

	/**
	 * @see #withOverrideReceivers(Recipient...)
	 */
	private final List<Recipient> overrideReceivers = new ArrayList<>();

	/**
	 * @see EmailStartingBuilder#forwarding(MimeMessage)
	 */
	private MimeMessage emailToForward;

	/**
	 * @see EmailPopulatingBuilder#getOriginalSmimeDetails()
	 */
	@NotNull
	private OriginalSmimeDetails originalSmimeDetails = new PlainSmimeDetails();
	@Nullable
	private DeliveryStatusReport deliveryStatusReport;

	@NotNull
	private OriginalOpenPgpDetails originalOpenPgpDetails = OpenPgpDetailsImpl.plain();

	/**
	 * @see EmailPopulatingBuilder#getSmimeSignedEmail()
	 */
	private Email smimeSignedEmail;

	/**
	 * @see EmailPopulatingBuilder#notMergingSingleSMIMESignedAttachment()
	 */
	private boolean mergeSingleSMIMESignedAttachment = true;

	/**
	 * @see EmailPopulatingBuilder#fixingSentDate(Date)
	 */
	@Nullable
	private Date sentDate;


    /** @see EmailPopulatingBuilder#withImageResolutionConfig(ImageResolutionConfig) */
    @Override
    public EmailPopulatingBuilder withImageResolutionConfig(@NotNull final ImageResolutionConfig choices) {
        requireNonNull(choices, "config");
        restoreCapturedImageResolution();
        if (choices.getEmbeddedImageAutoResolutionForFiles() != null) embeddedImageAutoResolutionForFiles = choices.getEmbeddedImageAutoResolutionForFiles();
        if (choices.getEmbeddedImageAutoResolutionForClassPathResources() != null) embeddedImageAutoResolutionForClassPathResources = choices.getEmbeddedImageAutoResolutionForClassPathResources();
        if (choices.getEmbeddedImageAutoResolutionForURLs() != null) embeddedImageAutoResolutionForURLs = choices.getEmbeddedImageAutoResolutionForURLs();
        if (choices.isEmbeddedImageBaseDirConfigured()) embeddedImageBaseDir = choices.getEmbeddedImageBaseDir();
        if (choices.isEmbeddedImageBaseClassPathConfigured()) embeddedImageBaseClassPath = choices.getEmbeddedImageBaseClassPath();
        if (choices.isEmbeddedImageBaseUrlConfigured()) embeddedImageBaseUrl = choices.getEmbeddedImageBaseUrl();
        if (choices.getAllowEmbeddedImageOutsideBaseDir() != null) allowEmbeddedImageOutsideBaseDir = choices.getAllowEmbeddedImageOutsideBaseDir();
        if (choices.getAllowEmbeddedImageOutsideBaseClassPath() != null) allowEmbeddedImageOutsideBaseClassPath = choices.getAllowEmbeddedImageOutsideBaseClassPath();
        if (choices.getAllowEmbeddedImageOutsideBaseUrl() != null) allowEmbeddedImageOutsideBaseUrl = choices.getAllowEmbeddedImageOutsideBaseUrl();
        if (choices.getEmbeddedImageAutoResolutionMustBeSuccesful() != null) embeddedImageAutoResolutionMustBeSuccesful = choices.getEmbeddedImageAutoResolutionMustBeSuccesful();
        imageResolutionConfig = choices;
        return this;
    }
    /** @see EmailPopulatingBuilder#withImageResolutionConfig(ImageResolutionConfig) */
    @Override @NotNull public ImageResolutionConfig getImageResolutionConfig() { return imageResolutionConfig; }
    /** @see EmailPopulatingBuilder#withImageResolutionConfig(ImageResolutionConfig) */
    @Override public EmailPopulatingBuilder useConfiguredImageResolutionConfig() { return withImageResolutionConfig(ImageResolutionConfig.builder().build()); }

    /** @see EmailPopulatingBuilder#withContentEncodingConfig(ContentEncodingConfig) */
    @Override
    public EmailPopulatingBuilder withContentEncodingConfig(@NotNull final ContentEncodingConfig choices) {
        requireNonNull(choices, "config");
        contentTransferEncoding = choices.getContentTransferEncoding();
        plainTextContentTransferEncoding = choices.getPlainTextContentTransferEncoding();
        htmlTextContentTransferEncoding = choices.getHTMLTextContentTransferEncoding();
        calendarTextContentTransferEncoding = choices.getCalendarTextContentTransferEncoding();
        return this;
    }
    /** @see EmailPopulatingBuilder#withContentEncodingConfig(ContentEncodingConfig) */
    @Override @NotNull public ContentEncodingConfig getContentEncodingConfig() {
        final ContentEncodingConfig.Builder choices = ContentEncodingConfig.builder();
        if (contentTransferEncoding != null) choices.withContentTransferEncoding(contentTransferEncoding);
        if (plainTextContentTransferEncoding != null) choices.withPlainTextContentTransferEncoding(plainTextContentTransferEncoding);
        if (htmlTextContentTransferEncoding != null) choices.withHTMLTextContentTransferEncoding(htmlTextContentTransferEncoding);
        if (calendarTextContentTransferEncoding != null) choices.withCalendarTextContentTransferEncoding(calendarTextContentTransferEncoding);
        return choices.build();
    }
    /** @see EmailPopulatingBuilder#withContentEncodingConfig(ContentEncodingConfig) */
    @Override public EmailPopulatingBuilder useConfiguredContentEncodingConfig() { return withContentEncodingConfig(ContentEncodingConfig.builder().build()); }

    /** @see EmailPopulatingBuilder#withTemplateOptions(EmailTemplateOptions) */
    @Override
    public EmailPopulatingBuilder withTemplateOptions(@NotNull final EmailTemplateOptions choices) {
        requireNonNull(choices, "config");
        ignoringDefaults = choices.isIgnoreDefaults();
        ignoringOverrides = choices.isIgnoreOverrides();
        propertiesNotToApplyDefaultValueFor = choices.getPropertiesNotToApplyDefaultValueFor() == null ? null : new HashSet<>(choices.getPropertiesNotToApplyDefaultValueFor());
        propertiesNotToApplyOverrideValueFor = choices.getPropertiesNotToApplyOverrideValueFor() == null ? null : new HashSet<>(choices.getPropertiesNotToApplyOverrideValueFor());
        return this;
    }
    /** @see EmailPopulatingBuilder#withTemplateOptions(EmailTemplateOptions) */
    @Override @NotNull public EmailTemplateOptions getTemplateOptions() {
        final EmailTemplateOptions.Builder choices = EmailTemplateOptions.builder().ignoringDefaults(ignoringDefaults).ignoringOverrides(ignoringOverrides);
        if (propertiesNotToApplyDefaultValueFor != null) choices.clearDefaultValueSuppressions().dontApplyDefaultValueFor(propertiesNotToApplyDefaultValueFor.toArray(new EmailProperty[0]));
        if (propertiesNotToApplyOverrideValueFor != null) choices.clearOverrideValueSuppressions().dontApplyOverrideValueFor(propertiesNotToApplyOverrideValueFor.toArray(new EmailProperty[0]));
        return choices.build();
    }
    /** @see EmailPopulatingBuilder#withTemplateOptions(EmailTemplateOptions) */
    @Override public EmailPopulatingBuilder useConfiguredTemplateOptions() { return withTemplateOptions(EmailTemplateOptions.builder().build()); }

    /** @see EmailPopulatingBuilder#withSmtpDeliveryOptions(SmtpDeliveryOptions) */
    @Override
    public EmailPopulatingBuilder withSmtpDeliveryOptions(@NotNull final SmtpDeliveryOptions choices) {
        requireNonNull(choices, "config");
        deliveryStatusNotification = choices.getDeliveryStatusNotification();
        tlsRequiredForOnwardDelivery = choices.isTlsRequiredForOnwardDelivery();
        sendingToAcceptedRecipients = choices.getSendingToAcceptedRecipients();
        return this;
    }
    /** @see EmailPopulatingBuilder#withSmtpDeliveryOptions(SmtpDeliveryOptions) */
    @Override @NotNull public SmtpDeliveryOptions getSmtpDeliveryOptions() {
        final SmtpDeliveryOptions.Builder choices = SmtpDeliveryOptions.builder().withDeliveryStatusNotification(deliveryStatusNotification);
        if (tlsRequiredForOnwardDelivery) choices.withTlsRequiredForOnwardDelivery();
        if (sendingToAcceptedRecipients != null) choices.withSendingToAcceptedRecipients(sendingToAcceptedRecipients);
        return choices.build();
    }
    /** @see EmailPopulatingBuilder#withSmtpDeliveryOptions(SmtpDeliveryOptions) */
    @Override public EmailPopulatingBuilder useConfiguredSmtpDeliveryOptions() { return withSmtpDeliveryOptions(SmtpDeliveryOptions.builder().build()); }


	/**
	 * @see EmailPopulatingBuilder#buildEmail()
	 */
	@Override
	@Cli.ExcludeApi(reason = "This API is specifically for Java use")
	public Email buildEmail() {
		validateLockedImageResolution();
		validateProtectionFamilies();
		resolveDynamicEmbeddedImageDataSources();
		//noinspection deprecation
		return new InternalEmail(this);
	}

	/** Check before resolution can read a file or URL; these locks govern this factory's resolver, not previously supplied attachments. */
	private void validateLockedImageResolution() {
		locksOf(config).verify(EMBEDDEDIMAGES_DYNAMICRESOLUTION_ENABLE_DIR, embeddedImageAutoResolutionForFiles);
		locksOf(config).verify(EMBEDDEDIMAGES_DYNAMICRESOLUTION_ENABLE_CLASSPATH, embeddedImageAutoResolutionForClassPathResources);
		locksOf(config).verify(EMBEDDEDIMAGES_DYNAMICRESOLUTION_ENABLE_URL, embeddedImageAutoResolutionForURLs);
		locksOf(config).verify(EMBEDDEDIMAGES_DYNAMICRESOLUTION_BASE_DIR, embeddedImageBaseDir);
		locksOf(config).verify(EMBEDDEDIMAGES_DYNAMICRESOLUTION_BASE_CLASSPATH, embeddedImageBaseClassPath);
		locksOf(config).verify(EMBEDDEDIMAGES_DYNAMICRESOLUTION_BASE_URL, embeddedImageBaseUrl == null ? null : embeddedImageBaseUrl.toExternalForm());
		locksOf(config).verify(EMBEDDEDIMAGES_DYNAMICRESOLUTION_OUTSIDE_BASE_DIR, allowEmbeddedImageOutsideBaseDir);
		locksOf(config).verify(EMBEDDEDIMAGES_DYNAMICRESOLUTION_OUTSIDE_BASE_CLASSPATH, allowEmbeddedImageOutsideBaseClassPath);
		locksOf(config).verify(EMBEDDEDIMAGES_DYNAMICRESOLUTION_OUTSIDE_BASE_URL, allowEmbeddedImageOutsideBaseUrl);
		locksOf(config).verify(EMBEDDEDIMAGES_DYNAMICRESOLUTION_MUSTBESUCCESFUL, embeddedImageAutoResolutionMustBeSuccesful);
	}

	private void validateProtectionFamilies() {
		if (openPgpSigningConfig == null && openPgpEncryptionConfig == null) {
			return;
		}
		final List<String> smimeSources = new ArrayList<>();
		if (smimeSigningConfig != null) {
			smimeSources.add("email-level S/MIME signing");
		}
		if (smimeEncryptionConfig != null) {
			smimeSources.add("email-level S/MIME encryption");
		}
		// Match the content recipients used by the MIME protection path; envelope overrides do not select encryption certificates.
		for (final Recipient recipient : recipients) {
			if ((recipient.getType() == RecipientType.TO || recipient.getType() == RecipientType.CC || recipient.getType() == RecipientType.BCC)
					&& recipient.getSmimeCertificate() != null) {
				smimeSources.add("S/MIME encryption requested by recipient " + recipient.getAddress() + " (" + recipient.getType() + ")");
				break;
			}
		}
		if (smimeSources.isEmpty()) {
			return;
		}
		final List<String> openPgpSources = new ArrayList<>();
		if (openPgpSigningConfig != null) {
			openPgpSources.add("email-level OpenPGP signing");
		}
		if (openPgpEncryptionConfig != null) {
			openPgpSources.add("email-level OpenPGP encryption");
		}
		throw new IllegalArgumentException("S/MIME and OpenPGP/MIME are both configured for this email: "
				+ String.join(", ", smimeSources) + "; " + String.join(", ", openPgpSources) + ". "
				+ "Simple Java Mail supports either format, but not wrapping one inside the other. "
				+ "Choose the format your recipients support. "
				+ "For email-level choices, use clearSmime() or clearOpenPgp() before buildEmail(). "
				+ "clearSmime() leaves recipient certificates intact; deliberately strip them from copied recipients with "
				+ "recipientsBuilder().withRecipients(...).clearingSmimeCertificates().buildRecipients() or remove the OpenPGP choice. "
				+ "If defaults or overrides supply it, adjust those templates or suppress that setting on the email being sent. "
				+ "Mandatory configuration locks still apply.");
	}

    EmailPopulatingBuilderImpl(@NotNull final SimpleJavaMailConfig config) {
        this.config = requireNonNull(config, "config");
        restoreCapturedImageResolution();
        imageResolutionConfig = ImageResolutionConfig.builder().build();
    }

    private void restoreCapturedImageResolution() {
        embeddedImageAutoResolutionForFiles = DEFAULT_EMBEDDED_IMAGE_AUTO_RESOLUTION_FOR_FILES;
        embeddedImageAutoResolutionForClassPathResources = DEFAULT_EMBEDDED_IMAGE_AUTO_RESOLUTION_FOR_CLASSPATH_RESOURCES;
        embeddedImageAutoResolutionForURLs = DEFAULT_EMBEDDED_IMAGE_AUTO_RESOLUTION_FOR_URLS;
        embeddedImageBaseDir = null;
        embeddedImageBaseClassPath = null;
        embeddedImageBaseUrl = null;
        allowEmbeddedImageOutsideBaseDir = DEFAULT_ALLOW_EMBEDDED_IMAGE_OUTSIDE_BASE_DIR;
        allowEmbeddedImageOutsideBaseClassPath = DEFAULT_ALLOW_EMBEDDED_IMAGE_OUTSIDE_BASE_CLASSPATH;
        allowEmbeddedImageOutsideBaseUrl = DEFAULT_ALLOW_EMBEDDED_IMAGE_OUTSIDE_BASE_URL;
        embeddedImageAutoResolutionMustBeSuccesful = DEFAULT_EMBEDDED_IMAGE_AUTO_RESOLUTION_MUST_BE_SUCCESSFUL;

		if (config.hasProperty(ConfigKeys.EMBEDDEDIMAGES_DYNAMICRESOLUTION_ENABLE_DIR)) {
			withEmbeddedImageAutoResolutionForFiles(verifyNonnullOrEmpty(config.get(ConfigKeys.EMBEDDEDIMAGES_DYNAMICRESOLUTION_ENABLE_DIR)));
		}
		if (config.hasProperty(ConfigKeys.EMBEDDEDIMAGES_DYNAMICRESOLUTION_ENABLE_CLASSPATH)) {
			withEmbeddedImageAutoResolutionForClassPathResources(verifyNonnullOrEmpty(config.get(ConfigKeys.EMBEDDEDIMAGES_DYNAMICRESOLUTION_ENABLE_CLASSPATH)));
		}
		if (config.hasProperty(ConfigKeys.EMBEDDEDIMAGES_DYNAMICRESOLUTION_ENABLE_URL)) {
			withEmbeddedImageAutoResolutionForURLs(verifyNonnullOrEmpty(config.get(ConfigKeys.EMBEDDEDIMAGES_DYNAMICRESOLUTION_ENABLE_URL)));
		}
		if (config.hasProperty(ConfigKeys.EMBEDDEDIMAGES_DYNAMICRESOLUTION_BASE_DIR)) {
			withEmbeddedImageBaseDir(verifyNonnullOrEmpty(config.get(ConfigKeys.EMBEDDEDIMAGES_DYNAMICRESOLUTION_BASE_DIR)));
		}
		if (config.hasProperty(ConfigKeys.EMBEDDEDIMAGES_DYNAMICRESOLUTION_BASE_CLASSPATH)) {
			withEmbeddedImageBaseClassPath(verifyNonnullOrEmpty(config.get(ConfigKeys.EMBEDDEDIMAGES_DYNAMICRESOLUTION_BASE_CLASSPATH)));
		}
		if (config.hasProperty(ConfigKeys.EMBEDDEDIMAGES_DYNAMICRESOLUTION_BASE_URL)) {
			withEmbeddedImageBaseUrl(verifyNonnullOrEmpty(config.get(ConfigKeys.EMBEDDEDIMAGES_DYNAMICRESOLUTION_BASE_URL)));
		}
		if (config.hasProperty(ConfigKeys.EMBEDDEDIMAGES_DYNAMICRESOLUTION_OUTSIDE_BASE_DIR)) {
			allowingEmbeddedImageOutsideBaseDir(verifyNonnullOrEmpty(config.get(ConfigKeys.EMBEDDEDIMAGES_DYNAMICRESOLUTION_OUTSIDE_BASE_DIR)));
		}
		if (config.hasProperty(ConfigKeys.EMBEDDEDIMAGES_DYNAMICRESOLUTION_OUTSIDE_BASE_CLASSPATH)) {
			allowingEmbeddedImageOutsideBaseClassPath(verifyNonnullOrEmpty(config.get(ConfigKeys.EMBEDDEDIMAGES_DYNAMICRESOLUTION_OUTSIDE_BASE_CLASSPATH)));
		}
		if (config.hasProperty(ConfigKeys.EMBEDDEDIMAGES_DYNAMICRESOLUTION_OUTSIDE_BASE_URL)) {
			allowingEmbeddedImageOutsideBaseUrl(verifyNonnullOrEmpty(config.get(ConfigKeys.EMBEDDEDIMAGES_DYNAMICRESOLUTION_OUTSIDE_BASE_URL)));
		}
		if (config.hasProperty(ConfigKeys.EMBEDDEDIMAGES_DYNAMICRESOLUTION_MUSTBESUCCESFUL)) {
			embeddedImageAutoResolutionMustBeSuccesful(verifyNonnullOrEmpty(config.get(ConfigKeys.EMBEDDEDIMAGES_DYNAMICRESOLUTION_MUSTBESUCCESFUL)));
		}
    }

	/**
	 * @see EmailPopulatingBuilder#buildEmailCompletedWithDefaultsAndOverrides()
	 */
	@Override
	@Cli.ExcludeApi(reason = "This API is specifically for Java use")
	public Email buildEmailCompletedWithDefaultsAndOverrides() {
		return buildEmailCompletedWithDefaultsAndOverrides(EmailGovernanceImpl.withConfig(config));
	}

	/**
	 * @see EmailPopulatingBuilder#buildEmailCompletedWithDefaultsAndOverrides(EmailGovernance)
	 */
	@Override
	@Cli.ExcludeApi(reason = "This API is specifically for Java use")
	public Email buildEmailCompletedWithDefaultsAndOverrides(@NotNull EmailGovernance emailGovernance) {
		return emailGovernance.produceEmailApplyingDefaultsAndOverrides(buildEmail());
	}

	private void resolveDynamicEmbeddedImageDataSources() {
		if (this.textHTML != null) {
			final Map<String, String> generatedCids = new HashMap<>();
			final StringBuffer stringBuffer = new StringBuffer();

			final Matcher matcher = IMG_SRC_PATTERN.matcher(this.textHTML);
			while (matcher.find()) {
				final String srcLocation = matcher.group("src");
				if (!srcLocation.startsWith("cid:")) {
					if (!generatedCids.containsKey(srcLocation)) {
						final DataSource dataSource = resolveDynamicEmbeddedImageDataSource(srcLocation);
						if (dataSource != null) {
							final String cid = randomCid10();
							generatedCids.put(srcLocation, cid);
							withEmbeddedImage(cid, new NamedDataSource(cid, dataSource));
						}
					}
					if (generatedCids.containsKey(srcLocation)) {
						final String imgSrcReplacement = matcher.group("imageTagStart") + "cid:" + generatedCids.get(srcLocation) + matcher.group("imageSrcEnd");
						matcher.appendReplacement(stringBuffer, quoteReplacement(imgSrcReplacement));
					}
				}
			}
			matcher.appendTail(stringBuffer);

			this.textHTML = stringBuffer.toString();
		}
	}

	@Nullable
	private DataSource resolveDynamicEmbeddedImageDataSource(@NotNull final String srcLocation) {
		try {
			DataSource resolvedDataSource = null;
			if (embeddedImageAutoResolutionForFiles) {
				resolvedDataSource = tryResolveImageFileDataSourceFromDisk(embeddedImageBaseDir, allowEmbeddedImageOutsideBaseDir, srcLocation);
			}
			if (resolvedDataSource == null && embeddedImageAutoResolutionForClassPathResources) {
				resolvedDataSource = tryResolveFileDataSourceFromClassPath(embeddedImageBaseClassPath, allowEmbeddedImageOutsideBaseClassPath, srcLocation);
			}
			if (resolvedDataSource == null && embeddedImageAutoResolutionForURLs) {
				resolvedDataSource = tryResolveUrlDataSource(embeddedImageBaseUrl, allowEmbeddedImageOutsideBaseUrl, srcLocation);
			}
			if (resolvedDataSource == null) {
				boolean autoresolutionWasAttempted = embeddedImageAutoResolutionForFiles || embeddedImageAutoResolutionForClassPathResources || embeddedImageAutoResolutionForURLs;
				if (!autoresolutionWasAttempted || !embeddedImageAutoResolutionMustBeSuccesful) {
					return null;
				}
			} else {
				return resolvedDataSource;
			}
		} catch (IOException e) {
			// unable to load datasource
		}
		throw new EmailException(format(ERROR_RESOLVING_IMAGE_DATASOURCE, srcLocation));
	}

	/**
	 * @see org.simplejavamail.api.email.config.EmailTemplateOptions.Builder#ignoringDefaults()
	 */
	@Override
	public EmailPopulatingBuilder ignoringDefaults() {
		return ignoringDefaults(true);
	}

	/**
	 * @see org.simplejavamail.api.email.config.EmailTemplateOptions.Builder#ignoringDefaults(boolean)
	 */
	@Override
	public EmailPopulatingBuilder ignoringDefaults(boolean ignoreDefaults) {
		this.ignoringDefaults = ignoreDefaults;
		return this;
	}

	/**
	 * @see org.simplejavamail.api.email.config.EmailTemplateOptions.Builder#ignoringOverrides()
	 */
	@Override
	public EmailPopulatingBuilder ignoringOverrides() {
		return ignoringOverrides(true);
	}

	/**
	 * @see org.simplejavamail.api.email.config.EmailTemplateOptions.Builder#ignoringOverrides(boolean)
	 */
	@Override
	public EmailPopulatingBuilder ignoringOverrides(boolean ignoreOverrides) {
		this.ignoringOverrides = ignoreOverrides;
		return this;
	}

	/**
	 * @see org.simplejavamail.api.email.config.EmailTemplateOptions.Builder#dontApplyDefaultValueFor(EmailProperty...)
	 */
	@Override
	public EmailPopulatingBuilder dontApplyDefaultValueFor(@NotNull EmailProperty @NotNull ...emailProperties) {
		if (emailProperties.length > 0) {
			if (propertiesNotToApplyDefaultValueFor == null) {
				propertiesNotToApplyDefaultValueFor = new HashSet<>();
			}
			propertiesNotToApplyDefaultValueFor.addAll(asList(emailProperties));
		}
		return this;
	}

	/**
	 * @see org.simplejavamail.api.email.config.EmailTemplateOptions.Builder#dontApplyOverrideValueFor(EmailProperty...)
	 */
	@Override
	public EmailPopulatingBuilder dontApplyOverrideValueFor(@NotNull EmailProperty @NotNull ...emailProperties) {
		if (emailProperties.length > 0) {
			if (propertiesNotToApplyOverrideValueFor == null) {
				propertiesNotToApplyOverrideValueFor = new HashSet<>();
			}
			propertiesNotToApplyOverrideValueFor.addAll(asList(emailProperties));
		}
		return this;
	}

	/** @see org.simplejavamail.api.email.config.EmailTemplateOptions.Builder#allowDefaultValueFor(EmailProperty...) */
	@Override
	public EmailPopulatingBuilder allowDefaultValueFor(@NotNull EmailProperty @NotNull ...emailProperties) {
		requireNonNull(emailProperties, "emailProperties");
		if (propertiesNotToApplyDefaultValueFor != null) {
			propertiesNotToApplyDefaultValueFor.removeAll(asList(emailProperties));
		}
		return this;
	}

	/** @see org.simplejavamail.api.email.config.EmailTemplateOptions.Builder#allowOverrideValueFor(EmailProperty...) */
	@Override
	public EmailPopulatingBuilder allowOverrideValueFor(@NotNull EmailProperty @NotNull ...emailProperties) {
		requireNonNull(emailProperties, "emailProperties");
		if (propertiesNotToApplyOverrideValueFor != null) {
			propertiesNotToApplyOverrideValueFor.removeAll(asList(emailProperties));
		}
		return this;
	}

	/** @see org.simplejavamail.api.email.config.EmailTemplateOptions.Builder#clearDefaultValueSuppressions() */
	@Override
	public EmailPopulatingBuilder clearDefaultValueSuppressions() {
		propertiesNotToApplyDefaultValueFor = new HashSet<>();
		return this;
	}

	/** @see org.simplejavamail.api.email.config.EmailTemplateOptions.Builder#clearOverrideValueSuppressions() */
	@Override
	public EmailPopulatingBuilder clearOverrideValueSuppressions() {
		propertiesNotToApplyOverrideValueFor = new HashSet<>();
		return this;
	}

	/**
	 * @see EmailPopulatingBuilder#fixingMessageId(String)
	 */
	@Override
	public EmailPopulatingBuilder fixingMessageId(@Nullable final String id) {
		this.id = id;
		return this;
	}
	
	/**
	 * @see EmailPopulatingBuilder#from(String)
	 */
	@Override
	@Cli.ExcludeApi(reason = "API is subset of another API")
	public EmailPopulatingBuilder from(@NotNull final String fromAddress) {
		return from(null, fromAddress);
	}
	
	/**
	 * @see EmailPopulatingBuilder#from(String, String)
	 */
	@Override
	public EmailPopulatingBuilder from(@Nullable final String fixedName, @NotNull final String fromAddress) {
		checkNonEmptyArgument(fromAddress, "fromAddress");
		return from(EmailAddressConvenience.interpret(fixedName, fromAddress, null, "fromAddress"));
	}
	
	/**
	 * @see EmailPopulatingBuilder#from(String, InternetAddress)
	 */
	@Override
	public EmailPopulatingBuilder from(@Nullable final String fixedName, @NotNull final InternetAddress fromAddress) {
		checkNonEmptyArgument(fromAddress, "fromAddress");
		return from(EmailAddressConvenience.interpret(fixedName, fromAddress, null, "fromAddress"));
	}
	
	/**
	 * @see EmailPopulatingBuilder#from(InternetAddress)
	 */
	@Override
	public EmailPopulatingBuilder from(@NotNull final InternetAddress fromAddress) {
		checkNonEmptyArgument(fromAddress, "fromAddress");
		return from(EmailAddressConvenience.interpret(null, fromAddress, null, "fromAddress"));
	}
	
	/**
	 * @see EmailPopulatingBuilder#from(Recipient)
	 */
	@Override
	public EmailPopulatingBuilder from(@NotNull final Recipient recipient) {
		checkNonEmptyArgument(recipient, "from recipient");
		this.fromRecipient = new Recipient(recipient.getName(), recipient.getAddress(), null, null);
		return this;
	}
	
	/**
	 * @see EmailPopulatingBuilder#withReplyTo(String)
	 */
	@Override
	@Cli.ExcludeApi(reason = "API is subset of another API")
	public EmailPopulatingBuilder withReplyTo(@NotNull final String replyToAddress) {
		return withReplyTo(EmailAddressConvenience.interpretList(null, replyToAddress, null, "replyToAddress"));
	}
	
	/**
	 * @see EmailPopulatingBuilder#withReplyTo(String, String)
	 */
	@Override
	public EmailPopulatingBuilder withReplyTo(@Nullable final String fixedName, @NotNull final String replyToAddress) {
		checkNonEmptyArgument(replyToAddress, "replyToAddress");
		return withReplyTo(EmailAddressConvenience.interpretList(fixedName, replyToAddress, null, "replyToAddress"));
	}
	
	/**
	 * @see EmailPopulatingBuilder#withReplyTo(InternetAddress)
	 */
	@Override
	public EmailPopulatingBuilder withReplyTo(@NotNull final InternetAddress replyToAddress) {
		checkNonEmptyArgument(replyToAddress, "replyToAddress");
		return withReplyTo(EmailAddressConvenience.interpretList(null, replyToAddress, null, "replyToAddress"));
	}
	
	/**
	 * @see EmailPopulatingBuilder#withReplyTo(String, InternetAddress)
	 */
	@Override
	public EmailPopulatingBuilder withReplyTo(@Nullable final String fixedName, @NotNull final InternetAddress replyToAddress) {
		checkNonEmptyArgument(replyToAddress, "replyToAddress");
		return withReplyTo(EmailAddressConvenience.interpretList(fixedName, replyToAddress, null, "replyToAddress"));
	}

	/**
	 * @see EmailPopulatingBuilder#withReplyTo(Recipient)
	 */
	@Override
	public EmailPopulatingBuilder withReplyTo(@NotNull final Recipient recipient) {
		this.replyToRecipients.add(new Recipient(recipient.getName(), recipient.getAddress(), null, null));
		return this;
	}

	/**
	 * @see EmailPopulatingBuilder#withReplyTo(List)
	 */
	@Override
	public EmailPopulatingBuilder withReplyTo(@NotNull final List<Recipient> recipients) {
		for (Recipient recipient : recipients) {
			this.replyToRecipients.add(new Recipient(recipient.getName(), recipient.getAddress(), null, null));
		}
		return this;
	}
	
	/**
	 * @see EmailPopulatingBuilder#withBounceTo(String)
	 */
	@Override
	@Cli.ExcludeApi(reason = "API is subset of another API")
	public EmailPopulatingBuilder withBounceTo(@Nullable final String bounceToAddress) {
		return withBounceTo(bounceToAddress != null ? EmailAddressConvenience.interpret(null, bounceToAddress, null, "bounceToAddress") : null);
	}
	
	/**
	 * @see EmailPopulatingBuilder#withBounceTo(String, String)
	 */
	@Override
	public EmailPopulatingBuilder withBounceTo(@Nullable final String name, @NotNull final String bounceToAddress) {
		checkNonEmptyArgument(bounceToAddress, "bounceToAddress");
		return withBounceTo(EmailAddressConvenience.interpret(name, bounceToAddress, null, "bounceToAddress"));
	}
	
	/**
	 * @see EmailPopulatingBuilder#withBounceTo(InternetAddress)
	 */
	@Override
	public EmailPopulatingBuilder withBounceTo(@NotNull final InternetAddress bounceToAddress) {
		checkNonEmptyArgument(bounceToAddress, "bounceToAddress");
		return withBounceTo(EmailAddressConvenience.interpret(null, bounceToAddress, null, "bounceToAddress"));
	}
	
	/**
	 * @see EmailPopulatingBuilder#withBounceTo(String, InternetAddress)
	 */
	@Override
	@Cli.ExcludeApi(reason = "Method is not detailed enough for CLI")
	public EmailPopulatingBuilder withBounceTo(@Nullable final String name, @NotNull final InternetAddress bounceToAddress) {
		checkNonEmptyArgument(bounceToAddress, "bounceToAddress");
		return withBounceTo(EmailAddressConvenience.interpret(name, bounceToAddress, null, "bounceToAddress"));
	}
	
	/**
	 * @see EmailPopulatingBuilder#withBounceTo(Recipient)
	 */
	@Override
	public EmailPopulatingBuilder withBounceTo(@Nullable final Recipient recipient) {
		this.bounceToRecipient = recipient != null ? new Recipient(recipient.getName(), recipient.getAddress(), null, null) : null;
		return this;
	}

	/**
	 * @see org.simplejavamail.api.email.config.SmtpDeliveryOptions.Builder#withDeliveryStatusNotification(DeliveryStatusNotification)
	 */
	@Override
	@Cli.ExcludeApi(reason = "This API is specifically for Java use")
	public EmailPopulatingBuilder withDeliveryStatusNotification(@Nullable final DeliveryStatusNotification deliveryStatusNotification) {
		this.deliveryStatusNotification = deliveryStatusNotification;
		return this;
	}

	/**
	 * @see org.simplejavamail.api.email.config.SmtpDeliveryOptions.Builder#withDeliveryStatusNotification(DeliveryStatusNotification.ReturnOption, DeliveryStatusNotification.NotifyOption...)
	 */
	@Override
	@Cli.ExcludeApi(reason = "This API is specifically for Java use")
	public EmailPopulatingBuilder withDeliveryStatusNotification(@Nullable final DeliveryStatusNotification.ReturnOption returnOption,
			@NotNull final DeliveryStatusNotification.NotifyOption @NotNull ...notifyOptions) {
		return withDeliveryStatusNotification(DeliveryStatusNotification.of(returnOption, notifyOptions));
	}

	/**
	 * @see org.simplejavamail.api.email.config.SmtpDeliveryOptions.Builder#withDeliveryStatusNotification(DeliveryStatusNotification.NotifyOption...)
	 */
	@Override
	@Cli.ExcludeApi(reason = "This API is specifically for Java use")
	public EmailPopulatingBuilder withDeliveryStatusNotification(@NotNull final DeliveryStatusNotification.NotifyOption @NotNull ...notifyOptions) {
		return withDeliveryStatusNotificationNotifyOptions(notifyOptions);
	}

	/**
	 * @see org.simplejavamail.api.email.config.SmtpDeliveryOptions.Builder#withDeliveryStatusNotificationNotifyOptions(DeliveryStatusNotification.NotifyOption...)
	 */
	@Override
	public EmailPopulatingBuilder withDeliveryStatusNotificationNotifyOptions(
			@NotNull final DeliveryStatusNotification.NotifyOption @NotNull ...notifyOptions) {
		return withDeliveryStatusNotification(deliveryStatusNotificationBuilder().notifyOptions(notifyOptions).build());
	}

	/**
	 * @see org.simplejavamail.api.email.config.SmtpDeliveryOptions.Builder#withDeliveryStatusNotificationReturnOption(DeliveryStatusNotification.ReturnOption)
	 */
	@Override
	public EmailPopulatingBuilder withDeliveryStatusNotificationReturnOption(@Nullable final DeliveryStatusNotification.ReturnOption returnOption) {
		if (returnOption == null && hasNoNotificationEventsOrEnvelopeIdentifier()) {
			deliveryStatusNotification = null;
			return this;
		}
		return withDeliveryStatusNotification(deliveryStatusNotificationBuilder().returnOption(returnOption).build());
	}

	/**
	 * @see org.simplejavamail.api.email.config.SmtpDeliveryOptions.Builder#fixingEnvelopeId(String)
	 */
	@Override
	public EmailPopulatingBuilder fixingEnvelopeId(@Nullable final String envelopeId) {
		if (envelopeId == null && hasNoReturnOptionOrNotificationEvents()) {
			return clearDeliveryStatusNotification();
		}
		return withDeliveryStatusNotification(deliveryStatusNotificationBuilder().envelopeId(envelopeId).build());
	}

	/** @see org.simplejavamail.api.email.config.SmtpDeliveryOptions.Builder#withTlsRequiredForOnwardDelivery() */
	@Override
	public EmailPopulatingBuilder withTlsRequiredForOnwardDelivery() {
		tlsRequiredForOnwardDelivery = true;
		return this;
	}

	/** @see org.simplejavamail.api.email.config.SmtpDeliveryOptions.Builder#withSendingToAcceptedRecipients(boolean) */
	@Override
	public EmailPopulatingBuilder withSendingToAcceptedRecipients(final boolean sendingToAcceptedRecipients) {
		this.sendingToAcceptedRecipients = sendingToAcceptedRecipients;
		return this;
	}

	private boolean hasNoNotificationEventsOrEnvelopeIdentifier() {
		return deliveryStatusNotification == null
				|| deliveryStatusNotification.getNotifyOptions().isEmpty() && deliveryStatusNotification.getEnvelopeId() == null;
	}

	private boolean hasNoReturnOptionOrNotificationEvents() {
		return deliveryStatusNotification == null
				|| deliveryStatusNotification.getReturnOption() == null && deliveryStatusNotification.getNotifyOptions().isEmpty();
	}

	private DeliveryStatusNotificationBuilder deliveryStatusNotificationBuilder() {
		return deliveryStatusNotification != null ? deliveryStatusNotification.toBuilder() : DeliveryStatusNotification.builder();
	}

	/**
	 * @see EmailPopulatingBuilder#withSubject(String)
	 */
	@Override
	public EmailPopulatingBuilder withSubject(@Nullable final String subject) {
		this.subject = subject;
		return this;
	}
	
	/**
	 * @see EmailStartingBuilder#forwarding(MimeMessage)
	 */
	@NotNull
	public InternalEmailPopulatingBuilder withForward(@Nullable final MimeMessage emailMessageToForward) {
		this.emailToForward = emailMessageToForward;
		return this;
	}
	
	/**
	 * @see EmailPopulatingBuilder#withPlainText(File)
	 */
	@Override
	@Cli.OptionNameOverride("withPlainTextFromFile")
	public EmailPopulatingBuilder withPlainText(@NotNull final File textFile) {
		return withPlainText(textFile.toPath());
	}

	/**
	 * @see EmailPopulatingBuilder#withPlainText(Path)
	 */
	@Override
	@Cli.ExcludeApi(reason = "The File overload already provides the same CLI file conversion")
	public EmailPopulatingBuilder withPlainText(@NotNull final Path textPath) {
		try {
			return withPlainText(FileUtil.readFileContent(textPath));
		} catch (IOException e) {
			throw new EmailException(format(ERROR_READING_FROM_FILE, textPath), e);
		}
	}
	
	/**
	 * @see EmailPopulatingBuilder#withPlainText(String)
	 */
	@Override
	public EmailPopulatingBuilder withPlainText(@Nullable final String text) {
		this.text = text;
		return this;
	}
	
	/**
	 * @see EmailPopulatingBuilder#prependPlainText(File)
	 */
	@Override
	@Cli.OptionNameOverride("prependTextFromFile")
	public EmailPopulatingBuilder prependPlainText(@NotNull final File textFile) {
		return prependPlainText(textFile.toPath());
	}

	/**
	 * @see EmailPopulatingBuilder#prependPlainText(Path)
	 */
	@Override
	@Cli.ExcludeApi(reason = "The File overload already provides the same CLI file conversion")
	public EmailPopulatingBuilder prependPlainText(@NotNull final Path textPath) {
		try {
			return prependPlainText(FileUtil.readFileContent(textPath));
		} catch (IOException e) {
			throw new EmailException(format(ERROR_READING_FROM_FILE, textPath), e);
		}
	}
	
	/**
	 * @see EmailPopulatingBuilder#prependPlainText(String)
	 */
	@Override
	public EmailPopulatingBuilder prependPlainText(@NotNull final String text) {
		this.text = text + defaultTo(this.text, "");
		return this;
	}
	
	/**
	 * @see EmailPopulatingBuilder#appendPlainText(File)
	 */
	@Override
	@Cli.OptionNameOverride("appendTextFromFile")
	public EmailPopulatingBuilder appendPlainText(@NotNull final File textFile) {
		return appendPlainText(textFile.toPath());
	}

	/**
	 * @see EmailPopulatingBuilder#appendPlainText(Path)
	 */
	@Override
	@Cli.ExcludeApi(reason = "The File overload already provides the same CLI file conversion")
	public EmailPopulatingBuilder appendPlainText(@NotNull final Path textPath) {
		try {
			return appendPlainText(FileUtil.readFileContent(textPath));
		} catch (IOException e) {
			throw new EmailException(format(ERROR_READING_FROM_FILE, textPath), e);
		}
	}
	
	/**
	 * @see EmailPopulatingBuilder#appendPlainText(String)
	 */
	@Override
	public EmailPopulatingBuilder appendPlainText(@NotNull final String text) {
		this.text = defaultTo(this.text, "") + text;
		return this;
	}
	
	/**
	 * @see EmailPopulatingBuilder#withHTMLText(File)
	 */
	@Override
	@Cli.OptionNameOverride("withHTMLTextFromFile")
	public EmailPopulatingBuilder withHTMLText(@NotNull final File textHTMLFile) {
		return withHTMLText(textHTMLFile.toPath());
	}

	/**
	 * @see EmailPopulatingBuilder#withHTMLText(Path)
	 */
	@Override
	@Cli.ExcludeApi(reason = "The File overload already provides the same CLI file conversion")
	public EmailPopulatingBuilder withHTMLText(@NotNull final Path textHTMLPath) {
		try {
			return withHTMLText(FileUtil.readFileContent(textHTMLPath));
		} catch (IOException e) {
			throw new EmailException(format(ERROR_READING_FROM_FILE, textHTMLPath), e);
		}
	}
	
	/**
	 * @see EmailPopulatingBuilder#withHTMLText(String)
	 */
	@Override
	public EmailPopulatingBuilder withHTMLText(@Nullable final String textHTML) {
		this.textHTML = textHTML;
		return this;
	}
	
	/**
	 * @see EmailPopulatingBuilder#prependHTMLText(File)
	 */
	@Override
	@Cli.OptionNameOverride("prependTextHTMLFromFile")
	public EmailPopulatingBuilder prependHTMLText(@NotNull final File textHTMLFile) {
		return prependHTMLText(textHTMLFile.toPath());
	}

	/**
	 * @see EmailPopulatingBuilder#prependHTMLText(Path)
	 */
	@Override
	@Cli.ExcludeApi(reason = "The File overload already provides the same CLI file conversion")
	public EmailPopulatingBuilder prependHTMLText(@NotNull final Path textHTMLPath) {
		try {
			return prependHTMLText(FileUtil.readFileContent(textHTMLPath));
		} catch (IOException e) {
			throw new EmailException(format(ERROR_READING_FROM_FILE, textHTMLPath), e);
		}
	}
	
	/**
	 * @see EmailPopulatingBuilder#prependHTMLText(String)
	 */
	@Override
	public EmailPopulatingBuilder prependHTMLText(@NotNull final String textHTML) {
		this.textHTML = textHTML + defaultTo(this.textHTML, "");
		return this;
	}
	
	/**
	 * @see EmailPopulatingBuilder#appendHTMLText(File)
	 */
	@Override
	@Cli.OptionNameOverride("appendTextHTMLFromFile")
	public EmailPopulatingBuilder appendHTMLText(@NotNull final File textHTMLFile) {
		return appendHTMLText(textHTMLFile.toPath());
	}

	/**
	 * @see EmailPopulatingBuilder#appendHTMLText(Path)
	 */
	@Override
	@Cli.ExcludeApi(reason = "The File overload already provides the same CLI file conversion")
	public EmailPopulatingBuilder appendHTMLText(@NotNull final Path textHTMLPath) {
		try {
			return appendHTMLText(FileUtil.readFileContent(textHTMLPath));
		} catch (IOException e) {
			throw new EmailException(format(ERROR_READING_FROM_FILE, textHTMLPath), e);
		}
	}
	
	/**
	 * @see EmailPopulatingBuilder#appendHTMLText(String)
	 */
	@Override
	public EmailPopulatingBuilder appendHTMLText(@NotNull final String textHTML) {
		this.textHTML = defaultTo(this.textHTML, "") + textHTML;
		return this;
	}
	
	/**
	 * @see EmailPopulatingBuilder#withCalendarText(CalendarMethod, String)
	 */
	@Override
	public EmailPopulatingBuilder withCalendarText(@NotNull final CalendarMethod calendarMethod, @NotNull final String textCalendar) {
		this.calendarMethod = calendarMethod;
		this.textCalendar = textCalendar;
		return this;
	}

	/**
	 * @see org.simplejavamail.api.email.config.ContentEncodingConfig.Builder#withContentTransferEncoding(ContentTransferEncoding)
	 */
	@Override
	public EmailPopulatingBuilder withContentTransferEncoding(@NotNull final ContentTransferEncoding contentTransferEncoding) {
		this.contentTransferEncoding = contentTransferEncoding;
		return this;
	}

	/**
	 * @see org.simplejavamail.api.email.config.ContentEncodingConfig.Builder#withPlainTextContentTransferEncoding(ContentTransferEncoding)
	 */
	@Override
	public EmailPopulatingBuilder withPlainTextContentTransferEncoding(@NotNull final ContentTransferEncoding contentTransferEncoding) {
		this.plainTextContentTransferEncoding = contentTransferEncoding;
		return this;
	}

	/**
	 * @see org.simplejavamail.api.email.config.ContentEncodingConfig.Builder#withHTMLTextContentTransferEncoding(ContentTransferEncoding)
	 */
	@Override
	public EmailPopulatingBuilder withHTMLTextContentTransferEncoding(@NotNull final ContentTransferEncoding contentTransferEncoding) {
		this.htmlTextContentTransferEncoding = contentTransferEncoding;
		return this;
	}

	/**
	 * @see org.simplejavamail.api.email.config.ContentEncodingConfig.Builder#withCalendarTextContentTransferEncoding(ContentTransferEncoding)
	 */
	@Override
	public EmailPopulatingBuilder withCalendarTextContentTransferEncoding(@NotNull final ContentTransferEncoding contentTransferEncoding) {
		this.calendarTextContentTransferEncoding = contentTransferEncoding;
		return this;
	}
	
	/**
	 * @see org.simplejavamail.api.internal.clisupport.CliEmailRecipientBuilder#withRecipients(String, boolean, RecipientType, String...)
	 */
	@Override
	@NotNull
	public InternalEmailPopulatingBuilder withRecipients(@Nullable String name, boolean fixedName, @Nullable RecipientType recipientType, @NotNull String @NotNull ... oneOrMoreAddressesEach) {
		for (String oneOrMoreAddresses : oneOrMoreAddressesEach) {
			recipients.addAll(MailboxInterpretation.interpretList(name, fixedName, oneOrMoreAddresses, recipientType, "oneOrMoreAddresses"));
		}
		return this;
	}

	/**
	 * @see EmailPopulatingBuilder#withRecipients(Collection)
	 */
	@Override
	public InternalEmailPopulatingBuilder withRecipients(@NotNull final Collection<Recipient> recipients) {
		for (final Recipient recipient : recipients) {
			this.recipients.add(requireNonNull(recipient, "recipient"));
		}
		return this;
	}
	
	/**
	 * @see EmailPopulatingBuilder#withRecipients(Recipient...)
	 */
	@Override
	public InternalEmailPopulatingBuilder withRecipients(@NotNull final Recipient @NotNull ... recipients) {
		return withRecipients(asList(recipients));
	}

	/** @see EmailPopulatingBuilder#to(String) */
	@Override
	public EmailPopulatingBuilder to(@NotNull final String address) {
		return to(null, address);
	}

	/** @see EmailPopulatingBuilder#to(String, String) */
	@Override
	public EmailPopulatingBuilder to(@Nullable final String name, @NotNull final String address) {
		return withRecipients(EmailAddressConvenience.interpretList(name, address, RecipientType.TO, "address"));
	}

	/** @see EmailPopulatingBuilder#cc(String) */
	@Override
	public EmailPopulatingBuilder cc(@NotNull final String address) {
		return cc(null, address);
	}

	/** @see EmailPopulatingBuilder#cc(String, String) */
	@Override
	public EmailPopulatingBuilder cc(@Nullable final String name, @NotNull final String address) {
		return withRecipients(EmailAddressConvenience.interpretList(name, address, RecipientType.CC, "address"));
	}

	/** @see EmailPopulatingBuilder#bcc(String) */
	@Override
	public EmailPopulatingBuilder bcc(@NotNull final String address) {
		return bcc(null, address);
	}

	/** @see EmailPopulatingBuilder#bcc(String, String) */
	@Override
	public EmailPopulatingBuilder bcc(@Nullable final String name, @NotNull final String address) {
		return withRecipients(EmailAddressConvenience.interpretList(name, address, RecipientType.BCC, "address"));
	}
	
	/**
	 * @see org.simplejavamail.api.email.config.ImageResolutionConfig.Builder#withEmbeddedImageBaseDir(String)
	 */
	@Override
	public EmailPopulatingBuilder withEmbeddedImageBaseDir(@NotNull final String embeddedImageBaseDir) {
		this.embeddedImageBaseDir = checkNonEmptyArgument(embeddedImageBaseDir, "embeddedImageBaseDir");
		imageResolutionConfig = imageResolutionConfig.toBuilder().withEmbeddedImageBaseDir(embeddedImageBaseDir).build();
		return this;
	}

	/**
	 * @see org.simplejavamail.api.email.config.ImageResolutionConfig.Builder#withEmbeddedImageAutoResolutionForFiles(boolean)
	 */
	@Override
	public EmailPopulatingBuilder withEmbeddedImageAutoResolutionForFiles(final boolean embeddedImageAutoResolutionForFiles) {
		this.embeddedImageAutoResolutionForFiles = embeddedImageAutoResolutionForFiles;
		imageResolutionConfig = imageResolutionConfig.toBuilder().withEmbeddedImageAutoResolutionForFiles(embeddedImageAutoResolutionForFiles).build();
		return this;
	}

	/**
	 * @see org.simplejavamail.api.email.config.ImageResolutionConfig.Builder#withEmbeddedImageAutoResolutionForClassPathResources(boolean)
	 */
	@Override
	public EmailPopulatingBuilder withEmbeddedImageAutoResolutionForClassPathResources(final boolean embeddedImageAutoResolutionForClassPathResources) {
		this.embeddedImageAutoResolutionForClassPathResources = embeddedImageAutoResolutionForClassPathResources;
		imageResolutionConfig = imageResolutionConfig.toBuilder().withEmbeddedImageAutoResolutionForClassPathResources(embeddedImageAutoResolutionForClassPathResources).build();
		return this;
	}

	/**
	 * @see org.simplejavamail.api.email.config.ImageResolutionConfig.Builder#withEmbeddedImageAutoResolutionForURLs(boolean)
	 */
	@Override
	public EmailPopulatingBuilder withEmbeddedImageAutoResolutionForURLs(final boolean embeddedImageAutoResolutionForURLs) {
		this.embeddedImageAutoResolutionForURLs = embeddedImageAutoResolutionForURLs;
		imageResolutionConfig = imageResolutionConfig.toBuilder().withEmbeddedImageAutoResolutionForURLs(embeddedImageAutoResolutionForURLs).build();
		return this;
	}

	/**
	 * @see org.simplejavamail.api.email.config.ImageResolutionConfig.Builder#withEmbeddedImageBaseClassPath(String)
	 */
	@Override
	public EmailPopulatingBuilder withEmbeddedImageBaseClassPath(@NotNull final String embeddedImageBaseClassPath) {
		this.embeddedImageBaseClassPath = checkNonEmptyArgument(embeddedImageBaseClassPath, "embeddedImageBaseClassPath");
		imageResolutionConfig = imageResolutionConfig.toBuilder().withEmbeddedImageBaseClassPath(embeddedImageBaseClassPath).build();
		return this;
	}

	/**
	 * @see org.simplejavamail.api.email.config.ImageResolutionConfig.Builder#withEmbeddedImageBaseUrl(String)
	 */
	@Override
	public EmailPopulatingBuilder withEmbeddedImageBaseUrl(@NotNull final String embeddedImageBaseUrl) {
		try {
			return withEmbeddedImageBaseUrl(new URL(embeddedImageBaseUrl));
		} catch (MalformedURLException e) {
			throw new EmailException(format(ERROR_PARSING_URL, embeddedImageBaseUrl), e);
		}
	}

	/**
	 * @see org.simplejavamail.api.email.config.ImageResolutionConfig.Builder#withEmbeddedImageBaseUrl(URL)
	 */
	@Override
	public EmailPopulatingBuilder withEmbeddedImageBaseUrl(@NotNull final URL embeddedImageBaseUrl) {
		this.embeddedImageBaseUrl = checkNonEmptyArgument(embeddedImageBaseUrl, "embeddedImageBaseUrl");
		imageResolutionConfig = imageResolutionConfig.toBuilder().withEmbeddedImageBaseUrl(embeddedImageBaseUrl).build();
		return this;
	}

	/**
	 * @see org.simplejavamail.api.email.config.ImageResolutionConfig.Builder#allowingEmbeddedImageOutsideBaseDir(boolean)
	 */
	@Override
	public EmailPopulatingBuilder allowingEmbeddedImageOutsideBaseDir(final boolean allowEmbeddedImageOutsideBaseDir) {
		this.allowEmbeddedImageOutsideBaseDir = allowEmbeddedImageOutsideBaseDir;
		imageResolutionConfig = imageResolutionConfig.toBuilder().allowingEmbeddedImageOutsideBaseDir(allowEmbeddedImageOutsideBaseDir).build();
		return this;
	}

	/**
	 * @see org.simplejavamail.api.email.config.ImageResolutionConfig.Builder#allowingEmbeddedImageOutsideBaseClassPath(boolean)
	 */
	@Override
	public EmailPopulatingBuilder allowingEmbeddedImageOutsideBaseClassPath(final boolean allowEmbeddedImageOutsideBaseClassPath) {
		this.allowEmbeddedImageOutsideBaseClassPath = allowEmbeddedImageOutsideBaseClassPath;
		imageResolutionConfig = imageResolutionConfig.toBuilder().allowingEmbeddedImageOutsideBaseClassPath(allowEmbeddedImageOutsideBaseClassPath).build();
		return this;
	}

	/**
	 * @see org.simplejavamail.api.email.config.ImageResolutionConfig.Builder#allowingEmbeddedImageOutsideBaseUrl(boolean)
	 */
	@Override
	public EmailPopulatingBuilder allowingEmbeddedImageOutsideBaseUrl(final boolean allowEmbeddedImageOutsideBaseUrl) {
		this.allowEmbeddedImageOutsideBaseUrl = allowEmbeddedImageOutsideBaseUrl;
		imageResolutionConfig = imageResolutionConfig.toBuilder().allowingEmbeddedImageOutsideBaseUrl(allowEmbeddedImageOutsideBaseUrl).build();
		return this;
	}

	/**
	 * @see org.simplejavamail.api.email.config.ImageResolutionConfig.Builder#embeddedImageAutoResolutionMustBeSuccesful(boolean)
	 */
	@Override
	public EmailPopulatingBuilder embeddedImageAutoResolutionMustBeSuccesful(final boolean embeddedImageAutoResolutionMustBeSuccesful) {
		this.embeddedImageAutoResolutionMustBeSuccesful = embeddedImageAutoResolutionMustBeSuccesful;
		imageResolutionConfig = imageResolutionConfig.toBuilder().embeddedImageAutoResolutionMustBeSuccesful(embeddedImageAutoResolutionMustBeSuccesful).build();
		return this;
	}

	/** @see EmailPopulatingBuilder#withEmbeddedImage(String, byte[], String) */
	@Override
	public EmailPopulatingBuilder withEmbeddedImage(@NotNull final String name, final byte@NotNull[] data, @NotNull final String mimetype) {
		checkNonEmptyArgument(name, "name");
		checkNonEmptyArgument(data, "data");
		return withEmbeddedImage(AttachmentResource.builder().withName(name).withData(data, mimetype).build());
	}

	/** @see EmailPopulatingBuilder#withPreEncodedEmbeddedImage(String, byte[], String, ContentTransferEncoding) */
	@Override
	public EmailPopulatingBuilder withPreEncodedEmbeddedImage(@NotNull final String name, final byte@NotNull[] data, @NotNull final String mimetype,
			@NotNull final ContentTransferEncoding preEncodedContentTransferEncoding) {
		checkNonEmptyArgument(name, "name");
		checkNonEmptyArgument(data, "data");
		return withEmbeddedImage(AttachmentResource.builder().withName(name).withData(data, mimetype)
				.withPreEncodedContentTransferEncoding(checkNonEmptyArgument(preEncodedContentTransferEncoding, "preEncodedContentTransferEncoding")).build());
	}

	/** @see EmailPopulatingBuilder#withEmbeddedImage(String, DataSource) */
	@Override
	public EmailPopulatingBuilder withEmbeddedImage(@Nullable final String name, @NotNull final DataSource imagedata) {
		return withEmbeddedImage(AttachmentResource.builder().withName(name).withDataSource(imagedata).build());
	}

	/** @see EmailPopulatingBuilder#withPreEncodedEmbeddedImage(String, DataSource, ContentTransferEncoding) */
	@Override
	public EmailPopulatingBuilder withPreEncodedEmbeddedImage(@Nullable final String name, @NotNull final DataSource imagedata,
			@NotNull final ContentTransferEncoding preEncodedContentTransferEncoding) {
		return withEmbeddedImage(AttachmentResource.builder().withName(name).withDataSource(imagedata)
				.withPreEncodedContentTransferEncoding(checkNonEmptyArgument(preEncodedContentTransferEncoding, "preEncodedContentTransferEncoding")).build());
	}

	/** @see EmailPopulatingBuilder#withEmbeddedImage(AttachmentResource) */
	@Override
	public EmailPopulatingBuilder withEmbeddedImage(@NotNull final AttachmentResource embeddedImage) {
		checkNonEmptyArgument(embeddedImage, "embeddedImage");
		validateEmbeddedImage(embeddedImage.getName(), embeddedImage.getDataSource(), embeddedImage.getContentId());
		embeddedImages.add(embeddedImage);
		return this;
	}

	private void validateEmbeddedImage(@Nullable final String name, @NotNull final DataSource imagedata, @Nullable final String contentId) {
		checkNonEmptyArgument(imagedata, "imagedata");
		if (valueNullOrEmpty(name) && valueNullOrEmpty(contentId) && valueNullOrEmpty(imagedata.getName())) {
			throw new EmailException(NAME_MISSING_FOR_EMBEDDED_IMAGE);
		}
	}

	/** @see EmailPopulatingBuilder#withEmbeddedImages(List) */
	@Override
	public EmailPopulatingBuilder withEmbeddedImages(@NotNull final List<AttachmentResource> embeddedImages) {
		for (final AttachmentResource embeddedImage : embeddedImages) {
			withEmbeddedImage(embeddedImage);
		}
		return this;
	}

	/**
	 * @see EmailPopulatingBuilder#withHeaders(Map)
	 */
	@Override
	public <T> EmailPopulatingBuilder withHeaders(@NotNull final Map<String, Collection<T>> headers) {
		return withHeaders(headers, false);
	}

	/**
	 * Copies headers, but if required will ignore generated Message-ID header, which should not be copied to a new message. This
	 * Message ID was never meant to be used for sending messages, but for handling nested S/MIME encrypted message attachments.
	 *
	 * @see EmailPopulatingBuilder#withHeaders(Map)
	 */
	@Override
	@NotNull
	public <T> InternalEmailPopulatingBuilder withHeaders(@NotNull final Map<String, Collection<T>> headers, final boolean ignoreSmimeMessageId) {
		for (Map.Entry<String, Collection<T>> headerEntry : headers.entrySet()) {
			for (final T headerValue : headerEntry.getValue()) {
				if (!ignoreSmimeMessageId ||
						!ModuleLoader.smimeModuleAvailable() ||
						!ModuleLoader.loadSmimeModule().isGeneratedSmimeMessageId(headerEntry.getKey(), headerValue)) {
					withHeader(headerEntry.getKey(), headerValue);
                }
			}
		}
		return this;
	}

	/**
	 * @see EmailPopulatingBuilder#withHeader(String, Object)
	 */
	@Override
	public EmailPopulatingBuilder withHeader(@NotNull final String name, @Nullable final Object value) {
		return withHeader(name, value, false);
	}

	/**
	 * @see EmailPopulatingBuilder#withHeader(String, Object, boolean)
	 */
	@Override
	public EmailPopulatingBuilder withHeader(@NotNull final String name, @Nullable final Object value, boolean replaceHeader) {
		checkNonEmptyArgument(name, "name");
		headers.putIfAbsent(name, new ArrayList<>());
		if (replaceHeader) {
			headers.get(name).clear();
		}
		val valueString = value != null ? String.valueOf(value) : null;
		if (!headers.get(name).contains(valueString)) {
			headers.get(name).add(valueString);
		}
		return this;
	}

	/** @see EmailPopulatingBuilder#withAttachment(String, byte[], String) */
	@Override
	public EmailPopulatingBuilder withAttachment(@Nullable final String name, final byte@NotNull[] data, @NotNull final String mimetype) {
		return withAttachment(AttachmentResource.builder().withName(name).withData(data, mimetype).build());
	}

	/** @see EmailPopulatingBuilder#withPreEncodedAttachment(String, byte[], String, ContentTransferEncoding) */
	@Override
	public EmailPopulatingBuilder withPreEncodedAttachment(@Nullable final String name, final byte@NotNull[] data, @NotNull final String mimetype,
			@NotNull final ContentTransferEncoding preEncodedContentTransferEncoding) {
		return withAttachment(AttachmentResource.builder().withName(name).withData(data, mimetype)
				.withPreEncodedContentTransferEncoding(checkNonEmptyArgument(preEncodedContentTransferEncoding, "preEncodedContentTransferEncoding")).build());
	}

	/** @see EmailPopulatingBuilder#withAttachment(String, DataSource) */
	@Override
	public EmailPopulatingBuilder withAttachment(@Nullable final String name, @NotNull final DataSource filedata) {
		return withAttachment(AttachmentResource.builder().withName(name).withDataSource(filedata).build());
	}

	/** @see EmailPopulatingBuilder#withAttachment(Path) */
	@Override
	public EmailPopulatingBuilder withAttachment(@NotNull final Path path) {
		return withAttachment(null, path);
	}

	/** @see EmailPopulatingBuilder#withAttachment(String, Path) */
	@Override
	public EmailPopulatingBuilder withAttachment(@Nullable final String name, @NotNull final Path path) {
		return withAttachment(AttachmentResource.builder().withName(name).withPath(path).build());
	}

	/** @see EmailPopulatingBuilder#withPreEncodedAttachment(String, DataSource, ContentTransferEncoding) */
	@Override
	public EmailPopulatingBuilder withPreEncodedAttachment(@Nullable final String name, @NotNull final DataSource filedata,
			@NotNull final ContentTransferEncoding preEncodedContentTransferEncoding) {
		return withAttachment(AttachmentResource.builder().withName(name).withDataSource(filedata)
				.withPreEncodedContentTransferEncoding(checkNonEmptyArgument(preEncodedContentTransferEncoding, "preEncodedContentTransferEncoding")).build());
	}

	/** @see org.simplejavamail.api.internal.clisupport.CliEmailConfigurationBuilder#withDescribedAttachment(String, DataSource, String) */
	@Override
	public EmailPopulatingBuilder withDescribedAttachment(@Nullable final String name, @NotNull final DataSource filedata, @Nullable final String description) {
		return withAttachment(AttachmentResource.builder().withName(name).withDataSource(filedata).withDescription(description).build());
	}

	/** @see org.simplejavamail.api.internal.clisupport.CliEmailConfigurationBuilder#withEncodedDescribedAttachment(String, DataSource, String, ContentTransferEncoding) */
	@Override
	public EmailPopulatingBuilder withEncodedDescribedAttachment(@Nullable final String name, @NotNull final DataSource filedata,
			@Nullable final String description, @Nullable final ContentTransferEncoding contentTransferEncoding) {
		return withAttachment(AttachmentResource.builder().withName(name).withDataSource(filedata).withDescription(description)
				.withContentTransferEncoding(contentTransferEncoding).build());
	}

	/** @see EmailPopulatingBuilder#withAttachment(AttachmentResource) */
	@Override
	public EmailPopulatingBuilder withAttachment(@NotNull final AttachmentResource attachment) {
		checkNonEmptyArgument(attachment, "attachment");
		checkNonEmptyArgument(attachment.getDataSource(), "filedata");
		attachments.add(attachment);
		return this;
	}

	/** @see EmailPopulatingBuilder#withAttachments(List) */
	@Override
	public EmailPopulatingBuilder withAttachments(@NotNull final List<AttachmentResource> attachments) {
		for (final AttachmentResource attachment : attachments) {
			withAttachment(attachment);
		}
		return this;
	}

	/**
	 * This is used internally.
	 *
	 * @see EmailPopulatingBuilder#getDecryptedAttachments()
	 */
	@NotNull
	@Override
	public InternalEmailPopulatingBuilder withDecryptedAttachments(@NotNull final List<AttachmentResource> attachments) {
		decryptedAttachments.addAll(attachments);
		return this;
	}

	/**
	 * This is used internally for testing purposed
	 *
	 * @see EmailPopulatingBuilder#getDecryptedAttachments()
	 */
	@NotNull
	@Override
	public InternalEmailPopulatingBuilder clearDecryptedAttachments() {
		decryptedAttachments.clear();
		return this;
	}
	
	/**
	 * @see EmailPopulatingBuilder#signWithDomainKey(DkimConfig)
	 */
	@Override
	@Cli.ExcludeApi(reason = "delegated method is an identical api from CLI point of view")
	public EmailPopulatingBuilder signWithDomainKey(@NotNull final DkimConfig dkimConfig) {
		this.dkimConfig = checkNonEmptyArgument(dkimConfig, "dkimConfig");
		return this;
	}

	/**
	 * @see org.simplejavamail.api.email.config.DkimConfig.DkimConfigBuilder#dkimPrivateKeyData(byte[])
	 */
	@Override
	public EmailPopulatingBuilder signWithDomainKey(final byte@NotNull[] dkimPrivateKey, @NotNull final String signingDomain, @NotNull final String dkimSelector, @Nullable final Set<String> excludedHeadersFromDkimDefaultSigningList) {
		return signWithDomainKey(DkimConfig.builder()
				.dkimPrivateKeyData(checkNonEmptyArgument(dkimPrivateKey, "dkimPrivateKey"))
				.dkimSigningDomain(checkNonEmptyArgument(signingDomain, "dkimSigningDomain"))
				.dkimSelector(checkNonEmptyArgument(dkimSelector, "dkimSelector"))
				.excludedHeadersFromDkimDefaultSigningList(excludedHeadersFromDkimDefaultSigningList)
				.build());
	}

	/**
	 * @param pkcs12StoreFile The file containing the keystore
	 * @param storePassword  The password to get keys from the store
	 * @param keyAlias The key we need for signing
	 * @param keyPassword The password for the key
	 *
	 * @see org.simplejavamail.api.email.config.SmimeSigningConfig.SmimeSigningConfigBuilder#pkcs12Config(File, String, String, String)
	 */
	@Override
	public EmailPopulatingBuilder signWithSmime(@NotNull final File pkcs12StoreFile, @NotNull final String storePassword, @NotNull final String keyAlias, @NotNull final String keyPassword, @Nullable final String signatureAlgorithm) {
		return signWithSmime(pkcs12StoreFile.toPath(), storePassword, keyAlias, keyPassword, signatureAlgorithm);
	}

	/**
	 * @see org.simplejavamail.api.email.config.SmimeSigningConfig.SmimeSigningConfigBuilder#pkcs12Config(Path, String, String, String)
	 */
	@Override
	@Cli.ExcludeApi(reason = "The File overload already provides the same CLI file conversion")
	public EmailPopulatingBuilder signWithSmime(@NotNull final Path pkcs12StorePath, @NotNull final String storePassword,
			@NotNull final String keyAlias, @NotNull final String keyPassword, @Nullable final String signatureAlgorithm) {
		return signWithSmime(SmimeSigningConfig.builder()
				.pkcs12Config(pkcs12StorePath, storePassword, keyAlias, keyPassword)
				.signatureAlgorithm(signatureAlgorithm)
				.build());
	}

	/**
	 * @see EmailPopulatingBuilder#signWithSmime(SmimeSigningConfig)
	 */
	@Override
	public EmailPopulatingBuilder signWithSmime(@NotNull final SmimeSigningConfig smimeSigningConfig) {
		this.smimeSigningConfig = smimeSigningConfig;
		return this;
	}

	/**
	 * @see org.simplejavamail.api.email.config.SmimeEncryptionConfig.SmimeEncryptionConfigBuilder#x509Certificate(File)
	 */
	@Override
	public EmailPopulatingBuilder encryptWithSmime(@NotNull final File pemFile, @Nullable final String keyEncapsulationAlgorithm, @Nullable final String cipherAlgorithm) {
		return encryptWithSmime(pemFile.toPath(), keyEncapsulationAlgorithm, cipherAlgorithm);
	}

	/**
	 * @see org.simplejavamail.api.email.config.SmimeEncryptionConfig.SmimeEncryptionConfigBuilder#x509Certificate(Path)
	 */
	@Override
	@Cli.ExcludeApi(reason = "The File overload already provides the same CLI file conversion")
	public EmailPopulatingBuilder encryptWithSmime(@NotNull final Path pemPath, @Nullable final String keyEncapsulationAlgorithm,
			@Nullable final String cipherAlgorithm) {
		return encryptWithSmime(SmimeEncryptionConfig.builder()
				.x509Certificate(pemPath)
				.keyEncapsulationAlgorithm(keyEncapsulationAlgorithm)
				.cipherAlgorithm(cipherAlgorithm)
				.build());
	}

	/**
	 * @see EmailPopulatingBuilder#encryptWithSmime(SmimeEncryptionConfig)
	 */
	@Override
	public EmailPopulatingBuilder encryptWithSmime(@NotNull final SmimeEncryptionConfig smimeEncryptionConfig) {
		this.smimeEncryptionConfig = smimeEncryptionConfig;
		return this;
	}

	/** @see EmailPopulatingBuilder#signWithOpenPgp(OpenPgpSigningConfig) */
	@Override
	public EmailPopulatingBuilder signWithOpenPgp(@NotNull final OpenPgpSigningConfig openPgpSigningConfig) {
		this.openPgpSigningConfig = checkNonEmptyArgument(openPgpSigningConfig, "openPgpSigningConfig");
		return this;
	}

	/** @see EmailPopulatingBuilder#encryptWithOpenPgp(OpenPgpEncryptionConfig) */
	@Override
	public EmailPopulatingBuilder encryptWithOpenPgp(@NotNull final OpenPgpEncryptionConfig openPgpEncryptionConfig) {
		this.openPgpEncryptionConfig = checkNonEmptyArgument(openPgpEncryptionConfig, "openPgpEncryptionConfig");
		return this;
	}

	private static List<Recipient> notificationRecipients(final Collection<Recipient> recipients) {
		requireNonNull(recipients, "recipients");
		final List<Recipient> destinations = new ArrayList<>();
		for (final Recipient recipient : recipients) {
			checkNonEmptyArgument(requireNonNull(recipient, "recipient").getAddress(), "recipient.address");
			destinations.add(new Recipient(recipient.getName(), recipient.getAddress(), null, null));
		}
		return destinations;
	}

	/**
	 * @see EmailPopulatingBuilder#withDispositionNotificationTo()
	 */
	@Override
	@Cli.OptionNameOverride("withDispositionNotificationToEnabled")
	public EmailPopulatingBuilder withDispositionNotificationTo() {
		this.useDispositionNotificationTo = true;
		this.dispositionNotificationToRecipients = new ArrayList<>();
		return this;
	}
	
	/**
	 * @see EmailPopulatingBuilder#withDispositionNotificationTo(String)
	 */
	@Override
	@Cli.ExcludeApi(reason = "API is subset of another API")
	public EmailPopulatingBuilder withDispositionNotificationTo(@NotNull final String address) {
		checkNonEmptyArgument(address, "dispositionNotificationToAddress");
		return withDispositionNotificationTo(EmailAddressConvenience.interpretList(null, address, null, "address"));
	}
	
	/**
	 * @see EmailPopulatingBuilder#withDispositionNotificationTo(String, String)
	 */
	@Override
	public EmailPopulatingBuilder withDispositionNotificationTo(@Nullable final String name, @NotNull final String address) {
		checkNonEmptyArgument(address, "dispositionNotificationToAddress");
		return withDispositionNotificationTo(EmailAddressConvenience.interpretList(name, address, null, "address"));
	}
	
	/**
	 * @see EmailPopulatingBuilder#withDispositionNotificationTo(InternetAddress)
	 */
	@Override
	public EmailPopulatingBuilder withDispositionNotificationTo(@NotNull final InternetAddress address) {
		checkNonEmptyArgument(address, "dispositionNotificationToAddress");
		return withDispositionNotificationTo(EmailAddressConvenience.interpretList(null, address, null, "address"));
	}
	
	/**
	 * @see EmailPopulatingBuilder#withDispositionNotificationTo(String, InternetAddress)
	 */
	@Override
	public EmailPopulatingBuilder withDispositionNotificationTo(@Nullable final String fixedName, @NotNull final InternetAddress address) {
		checkNonEmptyArgument(address, "dispositionNotificationToAddress");
		return withDispositionNotificationTo(EmailAddressConvenience.interpretList(fixedName, address, null, "address"));
	}
	
	/**
	 * @see EmailPopulatingBuilder#withDispositionNotificationTo(Recipient)
	 */
	/** @see EmailPopulatingBuilder#withDispositionNotificationTo(Collection) */
	@Override
	public EmailPopulatingBuilder withDispositionNotificationTo(@NotNull final Collection<Recipient> recipients) {
		final List<Recipient> destinations = notificationRecipients(recipients);
		this.useDispositionNotificationTo = true;
		this.dispositionNotificationToRecipients = destinations;
		return this;
	}

	@Override
	public EmailPopulatingBuilder withDispositionNotificationTo(@NotNull final Recipient recipient) {
		return withDispositionNotificationTo(java.util.Collections.singletonList(recipient));
	}
	
	/**
	 * @see EmailPopulatingBuilder#withReturnReceiptTo()
	 */
	@Override
	@Cli.OptionNameOverride("withReturnReceiptToEnabled")
	public EmailPopulatingBuilder withReturnReceiptTo() {
		this.useReturnReceiptTo = true;
		this.returnReceiptToRecipients = new ArrayList<>();
		return this;
	}
	
	/**
	 * @see EmailPopulatingBuilder#withReturnReceiptTo(String)
	 */
	@Override
	@Cli.ExcludeApi(reason = "API is subset of another API")
	public EmailPopulatingBuilder withReturnReceiptTo(@NotNull final String address) {
		checkNonEmptyArgument(address, "address");
		return withReturnReceiptTo(EmailAddressConvenience.interpretList(null, address, null, "address"));
	}
	
	/**
	 * @see EmailPopulatingBuilder#withReturnReceiptTo(String, String)
	 */
	@Override
	public EmailPopulatingBuilder withReturnReceiptTo(@Nullable final String name, @NotNull final String address) {
		checkNonEmptyArgument(address, "address");
		return withReturnReceiptTo(EmailAddressConvenience.interpretList(name, address, null, "address"));
	}
	
	/**
	 * @see EmailPopulatingBuilder#withReturnReceiptTo(InternetAddress)
	 */
	@Override
	public EmailPopulatingBuilder withReturnReceiptTo(@NotNull final InternetAddress address) {
		checkNonEmptyArgument(address, "address");
		return withReturnReceiptTo(EmailAddressConvenience.interpretList(null, address, null, "address"));
	}
	
	/**
	 * @see EmailPopulatingBuilder#withReturnReceiptTo(String, InternetAddress)
	 */
	@Override
	public EmailPopulatingBuilder withReturnReceiptTo(@Nullable final String fixedName, @NotNull final InternetAddress address) {
		checkNonEmptyArgument(address, "address");
		return withReturnReceiptTo(EmailAddressConvenience.interpretList(fixedName, address, null, "address"));
	}
	
	/**
	 * @see EmailPopulatingBuilder#withReturnReceiptTo(Recipient)
	 */
	/** @see EmailPopulatingBuilder#withReturnReceiptTo(Collection) */
	@Override
	public EmailPopulatingBuilder withReturnReceiptTo(@NotNull final Collection<Recipient> recipients) {
		final List<Recipient> destinations = notificationRecipients(recipients);
		this.useReturnReceiptTo = true;
		this.returnReceiptToRecipients = destinations;
		return this;
	}

	@Override
	public EmailPopulatingBuilder withReturnReceiptTo(@NotNull final Recipient recipient) {
		return withReturnReceiptTo(java.util.Collections.singletonList(recipient));
	}

	/**
	 * @see EmailPopulatingBuilder#withOverrideReceivers(Recipient...)
	 */
	@Override
	public EmailPopulatingBuilder withOverrideReceivers(@NotNull Recipient ...recipients) {
		checkNonEmptyArgument(recipients, "recipients");
		return withOverrideReceivers(asList(recipients));
	}

	/**
	 * @see EmailPopulatingBuilder#withOverrideReceivers(List)
	 */
	@Override
	public EmailPopulatingBuilder withOverrideReceivers(@NotNull List<Recipient> recipients) {
		checkNonEmptyArgument(recipients, "recipients");
		this.overrideReceivers.addAll(recipients);
		return this;
	}

	/**
	 * @see EmailPopulatingBuilder#getOriginalSmimeDetails()
	 */
	@NotNull
	@Override
	public InternalEmailPopulatingBuilder withOriginalSmimeDetails(@NotNull final OriginalSmimeDetails originalSmimeDetails) {
		this.originalSmimeDetails = originalSmimeDetails;
		return this;
	}

	@Override
	public InternalEmailPopulatingBuilder withOriginalOpenPgpDetails(@NotNull final OriginalOpenPgpDetails originalOpenPgpDetails) {
		this.originalOpenPgpDetails = originalOpenPgpDetails;
		return this;
	}

	/**
	 * @see EmailPopulatingBuilder#getSmimeSignedEmail()
	 */
	@NotNull
	@Override
	public InternalEmailPopulatingBuilder withSmimeSignedEmail(@NotNull final Email smimeSignedEmail) {
		this.smimeSignedEmail = smimeSignedEmail;
		return this;
	}

	/**
	 * @see EmailPopulatingBuilder#fixingSentDate(Date)
	 */
	@Override
	public EmailPopulatingBuilder fixingSentDate(@NotNull final Date sentDate) {
		this.sentDate = new Date(sentDate.getTime());
		return this;
	}

	/**
	 * @see EmailPopulatingBuilder#fixingSentDate(Instant)
	 */
	@Override
	@Cli.ExcludeApi(reason = "The Date overload already provides the same CLI value")
	public EmailPopulatingBuilder fixingSentDate(@NotNull final Instant sentDate) {
		return fixingSentDate(Date.from(sentDate));
	}

	/**
	 * @see EmailPopulatingBuilder#notMergingSingleSMIMESignedAttachment()
	 */
	@Override
	public EmailPopulatingBuilder notMergingSingleSMIMESignedAttachment() {
		this.mergeSingleSMIMESignedAttachment = false;
		return this;
	}

	/**
	 * @see EmailPopulatingBuilder#clearId()
	 */
	@Override
	public EmailPopulatingBuilder clearId() {
		this.id = null;
		return this;
	}
	
	/**
	 * @see EmailPopulatingBuilder#clearFromRecipient()
	 */
	@Override
	public EmailPopulatingBuilder clearFromRecipient() {
		this.fromRecipient = null;
		return this;
	}
	
	/**
	 * @see EmailPopulatingBuilder#clearReplyTo()
	 */
	@Override
	public EmailPopulatingBuilder clearReplyTo() {
		this.replyToRecipients.clear();
		return this;
	}
	
	/**
	 * @see EmailPopulatingBuilder#clearBounceTo()
	 */
	@Override
	public EmailPopulatingBuilder clearBounceTo() {
		this.bounceToRecipient = null;
		return this;
	}

	/**
	 * @see org.simplejavamail.api.email.config.SmtpDeliveryOptions.Builder#clearDeliveryStatusNotification()
	 */
	@Override
	public EmailPopulatingBuilder clearDeliveryStatusNotification() {
		this.deliveryStatusNotification = null;
		return this;
	}

	/** @see org.simplejavamail.api.email.config.SmtpDeliveryOptions.Builder#clearDeliveryStatusNotificationNotifyOptions() */
	@Override
	public EmailPopulatingBuilder clearDeliveryStatusNotificationNotifyOptions() {
		if (deliveryStatusNotification == null) {
			return this;
		}
		if (deliveryStatusNotification.getReturnOption() == null && deliveryStatusNotification.getEnvelopeId() == null) {
			return clearDeliveryStatusNotification();
		}
		return withDeliveryStatusNotification(deliveryStatusNotification.toBuilder().notifyOptions().build());
	}

	/** @see org.simplejavamail.api.email.config.SmtpDeliveryOptions.Builder#clearDeliveryStatusNotificationReturnOption() */
	@Override
	public EmailPopulatingBuilder clearDeliveryStatusNotificationReturnOption() {
		return withDeliveryStatusNotificationReturnOption(null);
	}

	/** @see org.simplejavamail.api.email.config.SmtpDeliveryOptions.Builder#clearEnvelopeId() */
	@Override
	public EmailPopulatingBuilder clearEnvelopeId() {
		return fixingEnvelopeId(null);
	}

	/** @see org.simplejavamail.api.email.config.SmtpDeliveryOptions.Builder#clearTlsRequiredForOnwardDelivery() */
	@Override
	public EmailPopulatingBuilder clearTlsRequiredForOnwardDelivery() {
		tlsRequiredForOnwardDelivery = false;
		return this;
	}

	/** @see org.simplejavamail.api.email.config.SmtpDeliveryOptions.Builder#clearSendingToAcceptedRecipients() */
	@Override
	public EmailPopulatingBuilder clearSendingToAcceptedRecipients() {
		sendingToAcceptedRecipients = null;
		return this;
	}

	/**
	 * @see EmailPopulatingBuilder#clearPlainText()
	 */
	@Override
	public EmailPopulatingBuilder clearPlainText() {
		this.text = null;
		return this;
	}

	/**
	 * @see EmailPopulatingBuilder#clearHTMLText()
	 */
	@Override
	public EmailPopulatingBuilder clearHTMLText() {
		this.textHTML = null;
		return this;
	}

	/**
	 * @see EmailPopulatingBuilder#clearCalendarText()
	 */
	@Override
	public EmailPopulatingBuilder clearCalendarText() {
		this.calendarMethod = null;
		this.textCalendar = null;
		return this;
	}

	/**
	 * @see org.simplejavamail.api.email.config.ContentEncodingConfig.Builder#clearContentTransferEncoding()
	 */
	@Override
	public EmailPopulatingBuilder clearContentTransferEncoding() {
		this.contentTransferEncoding = ContentTransferEncoding.QUOTED_PRINTABLE;
		return this;
	}

	/**
	 * @see org.simplejavamail.api.email.config.ContentEncodingConfig.Builder#clearPlainTextContentTransferEncoding()
	 */
	@Override
	public EmailPopulatingBuilder clearPlainTextContentTransferEncoding() {
		this.plainTextContentTransferEncoding = null;
		return this;
	}

	/**
	 * @see org.simplejavamail.api.email.config.ContentEncodingConfig.Builder#clearHTMLTextContentTransferEncoding()
	 */
	@Override
	public EmailPopulatingBuilder clearHTMLTextContentTransferEncoding() {
		this.htmlTextContentTransferEncoding = null;
		return this;
	}

	/**
	 * @see org.simplejavamail.api.email.config.ContentEncodingConfig.Builder#clearCalendarTextContentTransferEncoding()
	 */
	@Override
	public EmailPopulatingBuilder clearCalendarTextContentTransferEncoding() {
		this.calendarTextContentTransferEncoding = null;
		return this;
	}
	
	/**
	 * @see EmailPopulatingBuilder#clearSubject()
	 */
	@Override
	public EmailPopulatingBuilder clearSubject() {
		this.subject = null;
		return this;
	}
	
	/**
	 * @see EmailPopulatingBuilder#clearRecipients()
	 */
	@Override
	public EmailPopulatingBuilder clearRecipients() {
		this.recipients.clear();
		return this;
	}

	/**
	 * @see EmailPopulatingBuilder#clearOverrideReceivers()
	 */
	@Override
	public EmailPopulatingBuilder clearOverrideReceivers() {
		this.overrideReceivers.clear();
		return this;
	}

	/**
	 * @see org.simplejavamail.api.email.config.ImageResolutionConfig.Builder#clearEmbeddedImageBaseDir()
	 */
	public EmailPopulatingBuilder clearEmbeddedImageBaseDir() {
		this.embeddedImageBaseDir = null;
		imageResolutionConfig = imageResolutionConfig.toBuilder().clearEmbeddedImageBaseDir().build();
		return this;
	}

	/**
	 * @see org.simplejavamail.api.email.config.ImageResolutionConfig.Builder#clearEmbeddedImageBaseClassPath()
	 */
	public EmailPopulatingBuilder clearEmbeddedImageBaseClassPath() {
		this.embeddedImageBaseClassPath = null;
		imageResolutionConfig = imageResolutionConfig.toBuilder().clearEmbeddedImageBaseClassPath().build();
		return this;
	}

	/**
	 * @see org.simplejavamail.api.email.config.ImageResolutionConfig.Builder#clearEmbeddedImageBaseUrl()
	 */
	public EmailPopulatingBuilder clearEmbeddedImageBaseUrl() {
		this.embeddedImageBaseUrl = null;
		imageResolutionConfig = imageResolutionConfig.toBuilder().clearEmbeddedImageBaseUrl().build();
		return this;
	}
	
	/**
	 * @see EmailPopulatingBuilder#clearEmbeddedImages()
	 */
	@Override
	public EmailPopulatingBuilder clearEmbeddedImages() {
		this.embeddedImages.clear();
		return this;
	}
	
	/**
	 * @see EmailPopulatingBuilder#clearAttachments()
	 */
	@Override
	public EmailPopulatingBuilder clearAttachments() {
		this.attachments.clear();
		return this;
	}
	
	/**
	 * @see EmailPopulatingBuilder#clearHeaders()
	 */
	@Override
	public EmailPopulatingBuilder clearHeaders() {
		this.headers.clear();
		return this;
	}
	
	/**
	 * @see EmailPopulatingBuilder#clearDkim()
	 */
	@Override
	public EmailPopulatingBuilder clearDkim() {
		this.dkimConfig = null;
		return this;
	}

	/**
	 * See {@link EmailPopulatingBuilder#clearSmime()}
	 */
	@Override
	public EmailPopulatingBuilder clearSmime() {
		return clearSmimeSigning().clearSmimeEncryption();
	}

	/** @see EmailPopulatingBuilder#clearSmimeSigning() */
	@Override
	public EmailPopulatingBuilder clearSmimeSigning() {
		smimeSigningConfig = null;
		return this;
	}

	/** @see EmailPopulatingBuilder#clearSmimeEncryption() */
	@Override
	public EmailPopulatingBuilder clearSmimeEncryption() {
		smimeEncryptionConfig = null;
		return this;
	}

	/** @see EmailPopulatingBuilder#clearOpenPgp() */
	@Override
	public EmailPopulatingBuilder clearOpenPgp() {
		return clearOpenPgpSigning().clearOpenPgpEncryption();
	}

	/** @see EmailPopulatingBuilder#clearOpenPgpSigning() */
	@Override
	public EmailPopulatingBuilder clearOpenPgpSigning() {
		openPgpSigningConfig = null;
		return this;
	}

	/** @see EmailPopulatingBuilder#clearOpenPgpEncryption() */
	@Override
	public EmailPopulatingBuilder clearOpenPgpEncryption() {
		openPgpEncryptionConfig = null;
		return this;
	}

	/**
	 * @see EmailPopulatingBuilder#clearDispositionNotificationTo()
	 */
	@Override
	public EmailPopulatingBuilder clearDispositionNotificationTo() {
		return withDispositionNotificationToState(null, null);
	}

	/** @see InternalEmailPopulatingBuilder#withDispositionNotificationToState(Boolean, Collection) */
	@Override
	@NotNull
	public InternalEmailPopulatingBuilder withDispositionNotificationToState(@Nullable final Boolean requested, @Nullable final Collection<Recipient> recipients) {
		this.useDispositionNotificationTo = requested;
		this.dispositionNotificationToRecipients = recipients == null ? new ArrayList<>() : notificationRecipients(recipients);
		return this;
	}
	
	/**
	 * @see EmailPopulatingBuilder#clearReturnReceiptTo()
	 */
	@Override
	public EmailPopulatingBuilder clearReturnReceiptTo() {
		return withReturnReceiptToState(null, null);
	}

	/** @see InternalEmailPopulatingBuilder#withReturnReceiptToState(Boolean, Collection) */
	@Override
	@NotNull
	public InternalEmailPopulatingBuilder withReturnReceiptToState(@Nullable final Boolean requested, @Nullable final Collection<Recipient> recipients) {
		this.useReturnReceiptTo = requested;
		this.returnReceiptToRecipients = recipients == null ? new ArrayList<>() : notificationRecipients(recipients);
		return this;
	}

	/**
	 * @see EmailPopulatingBuilder#fixingSentDate(Date)
	 */
	@Override
	public EmailPopulatingBuilder clearSentDate() {
		this.sentDate = null;
		return this;
	}

	/**
	 * @see EmailPopulatingBuilder#clearSMIMESignedAttachmentMergingBehavior()
	 */
	@Override
	public EmailPopulatingBuilder clearSMIMESignedAttachmentMergingBehavior() {
		this.mergeSingleSMIMESignedAttachment = true;
		return this;
	}

	/**
	 * @see #ignoringDefaults(boolean)
	 */
	@Override
	public boolean isIgnoreDefaults() {
		return ignoringDefaults;
	}

	/**
	 * @see #ignoringOverrides(boolean)
	 */
	@Override
	public boolean isIgnoreOverrides() {
		return ignoringOverrides;
	}

	/**
	 * @see org.simplejavamail.api.email.config.EmailTemplateOptions.Builder#getPropertiesNotToApplyDefaultValueFor()
	 */
	@Override
	@Nullable
	public Set<EmailProperty> getPropertiesNotToApplyDefaultValueFor() {
		return propertiesNotToApplyDefaultValueFor == null ? null : new HashSet<>(propertiesNotToApplyDefaultValueFor);
	}

	/**
	 * @see org.simplejavamail.api.email.config.EmailTemplateOptions.Builder#getPropertiesNotToApplyOverrideValueFor()
	 */
	@Override
	@Nullable
	public Set<EmailProperty> getPropertiesNotToApplyOverrideValueFor() {
		return propertiesNotToApplyOverrideValueFor == null ? null : new HashSet<>(propertiesNotToApplyOverrideValueFor);
	}
	
	/*
		GETTERS
	 */
	
	/**
	 * @see EmailPopulatingBuilder#getId()
	 */
	@Override
	@Nullable
	public String getId() {
		return id;
	}
	
	/**
	 * @see EmailPopulatingBuilder#getFromRecipient()
	 */
	@Override
	@Nullable
	public Recipient getFromRecipient() {
		return fromRecipient;
	}
	
	/**
	 * @see EmailPopulatingBuilder#getReplyToRecipients()
	 */
	@Override
	@NotNull
	public List<Recipient> getReplyToRecipients() {
		return new ArrayList<>(replyToRecipients);
	}
	
	/**
	 * @see EmailPopulatingBuilder#getBounceToRecipient()
	 */
	@Override
	@Nullable
	public Recipient getBounceToRecipient() {
		return bounceToRecipient;
	}

	/**
	 * @see org.simplejavamail.api.email.config.SmtpDeliveryOptions.Builder#getDeliveryStatusNotification()
	 */
	@Override
	@Nullable
	public DeliveryStatusNotification getDeliveryStatusNotification() {
		return deliveryStatusNotification;
	}

	/** @see org.simplejavamail.api.email.config.SmtpDeliveryOptions.Builder#isTlsRequiredForOnwardDelivery() */
	@Override
	public boolean isTlsRequiredForOnwardDelivery() {
		return tlsRequiredForOnwardDelivery;
	}

	/** @see org.simplejavamail.api.email.config.SmtpDeliveryOptions.Builder#getSendingToAcceptedRecipients() */
	@Override
	@Nullable
	public Boolean getSendingToAcceptedRecipients() {
		return sendingToAcceptedRecipients;
	}

	/**
	 * @see EmailPopulatingBuilder#getPlainText()
	 */
	@Override
	@Nullable
	public String getPlainText() {
		return text;
	}
	
	/**
	 * @see EmailPopulatingBuilder#getHTMLText()
	 */
	@Override
	@Nullable
	public String getHTMLText() {
		return textHTML;
	}
	
	/**
	 * @see EmailPopulatingBuilder#getCalendarMethod()
	 */
	@Override
	@Nullable
	public CalendarMethod getCalendarMethod() {
		 return calendarMethod;
	}

	/**
	 * @see EmailPopulatingBuilder#getCalendarText()
	 */
	@Override
	@Nullable
	public String getCalendarText() {
		return textCalendar;
	}

	/**
	 * @see org.simplejavamail.api.email.config.ContentEncodingConfig.Builder#getContentTransferEncoding()
	 */
	@Override
	@Nullable
	public ContentTransferEncoding getContentTransferEncoding() {
		return contentTransferEncoding;
	}

	/**
	 * @see org.simplejavamail.api.email.config.ContentEncodingConfig.Builder#getPlainTextContentTransferEncoding()
	 */
	@Override
	@Nullable
	public ContentTransferEncoding getPlainTextContentTransferEncoding() {
		return plainTextContentTransferEncoding;
	}

	/**
	 * @see org.simplejavamail.api.email.config.ContentEncodingConfig.Builder#getHTMLTextContentTransferEncoding()
	 */
	@Override
	@Nullable
	public ContentTransferEncoding getHTMLTextContentTransferEncoding() {
		return htmlTextContentTransferEncoding;
	}

	/**
	 * @see org.simplejavamail.api.email.config.ContentEncodingConfig.Builder#getCalendarTextContentTransferEncoding()
	 */
	@Override
	@Nullable
	public ContentTransferEncoding getCalendarTextContentTransferEncoding() {
		return calendarTextContentTransferEncoding;
	}
	
	/**
	 * @see EmailPopulatingBuilder#getSubject()
	 */
	@Override
	@Nullable
	public String getSubject() {
		return subject;
	}
	
	/**
	 * @see EmailPopulatingBuilder#getRecipients()
	 */
	@NotNull
	@Override
	public List<Recipient> getRecipients() {
		return new ArrayList<>(recipients);
	}
	
	/**
	 * @see EmailPopulatingBuilder#getEmbeddedImages()
	 */
	@NotNull
	@Override
	public List<AttachmentResource> getEmbeddedImages() {
		return new ArrayList<>(embeddedImages);
	}
	
	/**
	 * @see EmailPopulatingBuilder#getAttachments()
	 */
	@NotNull
	@Override
	public List<AttachmentResource> getAttachments() {
		return new ArrayList<>(attachments);
	}

	/**
	 * @see EmailPopulatingBuilder#getDecryptedAttachments()
	 */
	@NotNull
	@Override
	public List<AttachmentResource> getDecryptedAttachments() {
		return new ArrayList<>(decryptedAttachments);
	}

	/**
	 * @see EmailPopulatingBuilder#getHeaders()
	 */
	@NotNull
	@Override
	public Map<String, Collection<String>> getHeaders() {
		final Map<String, Collection<String>> snapshot = new HashMap<>();
		headers.forEach((name, values) -> snapshot.put(name, new ArrayList<>(values)));
		return snapshot;
	}
	
	/**
	 * @see EmailPopulatingBuilder#getDkimConfig()
	 */
	@Override
	@Nullable
	public DkimConfig getDkimConfig() {
		return dkimConfig;
	}
	
	/**
	 * @see EmailPopulatingBuilder#getUseDispositionNotificationTo()
	 */
	@Override
	@Nullable
	public Boolean getUseDispositionNotificationTo() {
		return useDispositionNotificationTo;
	}
	
	/**
	 * @see EmailPopulatingBuilder#getDispositionNotificationToRecipients()
	 */
	@Override
	@NotNull
	public List<Recipient> getDispositionNotificationToRecipients() {
		return java.util.Collections.unmodifiableList(new ArrayList<>(dispositionNotificationToRecipients));
	}
	
	/**
	 * @see EmailPopulatingBuilder#getUseReturnReceiptTo()
	 */
	@Override
	@Nullable
	public Boolean getUseReturnReceiptTo() {
		return useReturnReceiptTo;
	}

	/**
	 * @see EmailPopulatingBuilder#getReturnReceiptToRecipients()
	 */
	@Override
	@NotNull
	public List<Recipient> getReturnReceiptToRecipients() {
		return java.util.Collections.unmodifiableList(new ArrayList<>(returnReceiptToRecipients));
	}

	/**
	 * @see EmailPopulatingBuilder#getOverrideReceivers()
	 */
	@Override
	@NotNull
	public List<Recipient> getOverrideReceivers() {
		return new ArrayList<>(overrideReceivers);
	}
	
	/**
	 * @see EmailPopulatingBuilder#getEmailToForward()
	 */
	@Override
	@Nullable
	public MimeMessage getEmailToForward() {
		return emailToForward;
	}

	/**
	 * @see EmailPopulatingBuilder#getOriginalSmimeDetails()
	 */
	@NotNull
	@Override
	public OriginalSmimeDetails getOriginalSmimeDetails() {
		return originalSmimeDetails;
	}

	/** @see Email#getDeliveryStatusReport() */
	@Override
	@Nullable
	public DeliveryStatusReport getDeliveryStatusReport() {
		return deliveryStatusReport;
	}

	@Override
	@NotNull
	public InternalEmailPopulatingBuilder withDeliveryStatusReport(@Nullable final DeliveryStatusReport deliveryStatusReport) {
		this.deliveryStatusReport = deliveryStatusReport;
		return this;
	}

	/**
	 * @see EmailPopulatingBuilder#getSmimeSignedEmail()
	 */
	@Nullable
	@Override
	public Email getSmimeSignedEmail() {
		return smimeSignedEmail;
	}

	/**
	 * @see EmailPopulatingBuilder#isMergeSingleSMIMESignedAttachment()
	 */
	@Override
	public boolean isMergeSingleSMIMESignedAttachment() {
		return mergeSingleSMIMESignedAttachment;
	}

	/**
	 * @see EmailPopulatingBuilder#getSmimeSigningConfig()
	 */
	@Override
	@Nullable
	public SmimeSigningConfig getSmimeSigningConfig() {
		return smimeSigningConfig;
	}

	/**
	 * @see EmailPopulatingBuilder#getSmimeEncryptionConfig()
	 */
	@Override
	@Nullable
	public SmimeEncryptionConfig getSmimeEncryptionConfig() {
		return smimeEncryptionConfig;
	}

	/** @see EmailPopulatingBuilder#getOpenPgpSigningConfig() */
	@Override
	@Nullable
	public OpenPgpSigningConfig getOpenPgpSigningConfig() {
		return openPgpSigningConfig;
	}

	/** @see EmailPopulatingBuilder#getOpenPgpEncryptionConfig() */
	@Override
	@Nullable
	public OpenPgpEncryptionConfig getOpenPgpEncryptionConfig() {
		return openPgpEncryptionConfig;
	}

	@Override
	@NotNull
	public OriginalOpenPgpDetails getOriginalOpenPgpDetails() {
		return originalOpenPgpDetails;
	}

	/**
	 * @see EmailPopulatingBuilder#fixingSentDate(Date)
	 */
	@Override
	@Nullable
	public Date getSentDate() {
		return sentDate != null ? new Date(sentDate.getTime()) : null;
	}

	/**
	 * @see EmailPopulatingBuilder#getSentDateAsInstant()
	 */
	@Override
	@Nullable
	public Instant getSentDateAsInstant() {
		return sentDate != null ? sentDate.toInstant() : null;
	}
}
