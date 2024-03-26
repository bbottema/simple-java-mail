package org.simplejavamail.email.internal;

import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;
import jakarta.activation.DataSource;
import jakarta.mail.Message.RecipientType;
import jakarta.mail.internet.InternetAddress;
import jakarta.mail.internet.MimeMessage;
import jakarta.mail.util.ByteArrayDataSource;
import lombok.val;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.simplejavamail.api.email.AttachmentResource;
import org.simplejavamail.api.email.CalendarMethod;
import org.simplejavamail.api.email.ContentTransferEncoding;
import org.simplejavamail.api.email.Email;
import org.simplejavamail.api.email.EmailPopulatingBuilder;
import org.simplejavamail.api.email.EmailStartingBuilder;
import org.simplejavamail.api.email.OriginalSmimeDetails;
import org.simplejavamail.api.email.Recipient;
import org.simplejavamail.api.email.config.DkimConfig;
import org.simplejavamail.api.email.config.SmimeEncryptionConfig;
import org.simplejavamail.api.email.config.SmimeSigningConfig;
import org.simplejavamail.api.internal.clisupport.model.Cli;
import org.simplejavamail.api.internal.smimesupport.model.PlainSmimeDetails;
import org.simplejavamail.api.mailer.config.EmailGovernance;
import org.simplejavamail.email.EmailBuilder;
import org.simplejavamail.internal.config.EmailProperty;
import org.simplejavamail.internal.moduleloader.ModuleLoader;
import org.simplejavamail.internal.util.FileUtil;
import org.simplejavamail.internal.util.MiscUtil;
import org.simplejavamail.internal.util.NamedDataSource;

import java.io.File;
import java.io.IOException;
import java.net.MalformedURLException;
import java.net.URL;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Date;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;

import static jakarta.mail.Message.RecipientType.BCC;
import static jakarta.mail.Message.RecipientType.CC;
import static jakarta.mail.Message.RecipientType.TO;
import static java.lang.String.format;
import static java.util.Arrays.asList;
import static java.util.Collections.singletonList;
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
import static org.simplejavamail.config.ConfigLoader.getBooleanProperty;
import static org.simplejavamail.config.ConfigLoader.getStringProperty;
import static org.simplejavamail.config.ConfigLoader.hasProperty;
import static org.simplejavamail.email.internal.EmailException.ERROR_PARSING_URL;
import static org.simplejavamail.email.internal.EmailException.ERROR_READING_FROM_FILE;
import static org.simplejavamail.email.internal.EmailException.ERROR_RESOLVING_IMAGE_DATASOURCE;
import static org.simplejavamail.email.internal.EmailException.NAME_MISSING_FOR_EMBEDDED_IMAGE;
import static org.simplejavamail.internal.util.MiscUtil.defaultTo;
import static org.simplejavamail.internal.util.MiscUtil.extractEmailAddresses;
import static org.simplejavamail.internal.util.MiscUtil.interpretRecipient;
import static org.simplejavamail.internal.util.MiscUtil.randomCid10;
import static org.simplejavamail.internal.util.MiscUtil.tryResolveFileDataSourceFromClassPath;
import static org.simplejavamail.internal.util.MiscUtil.tryResolveImageFileDataSourceFromDisk;
import static org.simplejavamail.internal.util.MiscUtil.tryResolveUrlDataSource;
import static org.simplejavamail.internal.util.MiscUtil.valueNullOrEmpty;
import static org.simplejavamail.internal.util.Preconditions.checkNonEmptyArgument;
import static org.simplejavamail.internal.util.Preconditions.verifyNonnullOrEmpty;
import static org.simplejavamail.mailer.internal.EmailGovernanceImpl.NO_GOVERNANCE;

/**
 * @see EmailPopulatingBuilder
 */
@SuppressWarnings({"UnusedReturnValue", "unused"})
public class EmailPopulatingBuilderImpl implements InternalEmailPopulatingBuilder {

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
	 * @see #to(Recipient...)
	 * @see #cc(Recipient...)
	 * @see #bcc(Recipient...)
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
	private final Map<String, Collection<String>> headers = new HashMap<>();

	/**
	 * @see #signWithDomainKey(DkimConfig)
	 * @see #signWithDomainKey(byte[], String, String, Set)
	 */
	@Nullable
	private DkimConfig dkimConfig;

	/**
	 * @see #signWithSmime(SmimeSigningConfig)
	 * @see #signWithSmime(File, String, String, String, String)
	 * @see #encryptWithSmime(SmimeEncryptionConfig)
	 * @see #encryptWithSmime(File, String, String)
	 */
	@Nullable
	private SmimeSigningConfig smimeSigningConfig;

	/**
	 * @see #encryptWithSmime(SmimeEncryptionConfig)
	 * @see #encryptWithSmime(File, String, String)
	 * @see #signWithSmime(SmimeSigningConfig)
	 * @see #signWithSmime(File, String, String, String, String)
	 */
	@Nullable
	private SmimeEncryptionConfig smimeEncryptionConfig;

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
	private Recipient dispositionNotificationTo;

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
	private Recipient returnReceiptTo;

	/**
	 * @see #withOverrideReceivers(Recipient...)
	 */
	private final List<Recipient> overrideReceivers = new ArrayList<>();

	/**
	 * @see EmailBuilder#forwarding(MimeMessage)
	 */
	private MimeMessage emailToForward;

	/**
	 * @see EmailPopulatingBuilder#getOriginalSmimeDetails()
	 */
	@NotNull
	private OriginalSmimeDetails originalSmimeDetails = new PlainSmimeDetails();

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

	/**
	 * @see EmailStartingBuilder#startingBlank()
	 */
	EmailPopulatingBuilderImpl() {
			if (hasProperty(EMBEDDEDIMAGES_DYNAMICRESOLUTION_ENABLE_DIR)) {
				withEmbeddedImageAutoResolutionForFiles(verifyNonnullOrEmpty(getBooleanProperty(EMBEDDEDIMAGES_DYNAMICRESOLUTION_ENABLE_DIR)));
			}
			if (hasProperty(EMBEDDEDIMAGES_DYNAMICRESOLUTION_ENABLE_CLASSPATH)) {
				withEmbeddedImageAutoResolutionForClassPathResources(verifyNonnullOrEmpty(getBooleanProperty(EMBEDDEDIMAGES_DYNAMICRESOLUTION_ENABLE_CLASSPATH)));
			}
			if (hasProperty(EMBEDDEDIMAGES_DYNAMICRESOLUTION_ENABLE_URL)) {
				withEmbeddedImageAutoResolutionForURLs(verifyNonnullOrEmpty(getBooleanProperty(EMBEDDEDIMAGES_DYNAMICRESOLUTION_ENABLE_URL)));
			}
			if (hasProperty(EMBEDDEDIMAGES_DYNAMICRESOLUTION_BASE_DIR)) {
				withEmbeddedImageBaseDir(verifyNonnullOrEmpty(getStringProperty(EMBEDDEDIMAGES_DYNAMICRESOLUTION_BASE_DIR)));
			}
			if (hasProperty(EMBEDDEDIMAGES_DYNAMICRESOLUTION_BASE_CLASSPATH)) {
				withEmbeddedImageBaseClassPath(verifyNonnullOrEmpty(getStringProperty(EMBEDDEDIMAGES_DYNAMICRESOLUTION_BASE_CLASSPATH)));
			}
			if (hasProperty(EMBEDDEDIMAGES_DYNAMICRESOLUTION_BASE_URL)) {
				withEmbeddedImageBaseUrl(verifyNonnullOrEmpty(getStringProperty(EMBEDDEDIMAGES_DYNAMICRESOLUTION_BASE_URL)));
			}
			if (hasProperty(EMBEDDEDIMAGES_DYNAMICRESOLUTION_OUTSIDE_BASE_DIR)) {
				allowingEmbeddedImageOutsideBaseDir(verifyNonnullOrEmpty(getBooleanProperty(EMBEDDEDIMAGES_DYNAMICRESOLUTION_OUTSIDE_BASE_DIR)));
			}
			if (hasProperty(EMBEDDEDIMAGES_DYNAMICRESOLUTION_OUTSIDE_BASE_URL)) {
				allowingEmbeddedImageOutsideBaseClassPath(verifyNonnullOrEmpty(getBooleanProperty(EMBEDDEDIMAGES_DYNAMICRESOLUTION_OUTSIDE_BASE_URL)));
			}
			if (hasProperty(EMBEDDEDIMAGES_DYNAMICRESOLUTION_OUTSIDE_BASE_CLASSPATH)) {
				allowingEmbeddedImageOutsideBaseUrl(verifyNonnullOrEmpty(getBooleanProperty(EMBEDDEDIMAGES_DYNAMICRESOLUTION_OUTSIDE_BASE_CLASSPATH)));
			}
			if (hasProperty(EMBEDDEDIMAGES_DYNAMICRESOLUTION_MUSTBESUCCESFUL)) {
				embeddedImageAutoResolutionMustBeSuccesful(verifyNonnullOrEmpty(getBooleanProperty(EMBEDDEDIMAGES_DYNAMICRESOLUTION_MUSTBESUCCESFUL)));
			}
	}
	/**
	 * @see EmailPopulatingBuilder#buildEmail()
	 */
	@Override
	@Cli.ExcludeApi(reason = "This API is specifically for Java use")
	public Email buildEmail() {
		validateDkim();
		resolveDynamicEmbeddedImageDataSources();
		//noinspection deprecation
		return new InternalEmail(this);
	}

	/**
	 * @see EmailPopulatingBuilder#buildEmailCompletedWithDefaultsAndOverrides()
	 */
	@Override
	@Cli.ExcludeApi(reason = "This API is specifically for Java use")
	public Email buildEmailCompletedWithDefaultsAndOverrides() {
		return buildEmailCompletedWithDefaultsAndOverrides(NO_GOVERNANCE());
	}

	/**
	 * @see EmailPopulatingBuilder#buildEmailCompletedWithDefaultsAndOverrides(EmailGovernance)
	 */
	@Override
	@Cli.ExcludeApi(reason = "This API is specifically for Java use")
	public Email buildEmailCompletedWithDefaultsAndOverrides(@NotNull EmailGovernance emailGovernance) {
		return emailGovernance.produceEmailApplyingDefaultsAndOverrides(buildEmail());
	}

	private void validateDkim() {
		if (getDkimConfig() != null) {
			checkNonEmptyArgument(getFromRecipient(), "fromRecipient required when signing DKIM");
		}
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
	 * @see EmailPopulatingBuilder#ignoringDefaults(boolean)
	 */
	@Override
	public EmailPopulatingBuilder ignoringDefaults(boolean ignoreDefaults) {
		this.ignoringDefaults = ignoreDefaults;
		return this;
	}

	/**
	 * @see EmailPopulatingBuilder#ignoringOverrides(boolean)
	 */
	@Override
	public EmailPopulatingBuilder ignoringOverrides(boolean ignoreDefaults) {
		this.ignoringOverrides = ignoreDefaults;
		return this;
	}

	/**
	 * @see EmailPopulatingBuilder#dontApplyDefaultValueFor(EmailProperty...)
	 */
	@Override
	public EmailPopulatingBuilder dontApplyDefaultValueFor(@NotNull EmailProperty @NotNull ...emailProperties) {
		this.propertiesNotToApplyDefaultValueFor = new HashSet<>(asList(emailProperties));
		return this;
	}

	/**
	 * @see EmailPopulatingBuilder#dontApplyOverrideValueFor(EmailProperty...)
	 */
	@Override
	public EmailPopulatingBuilder dontApplyOverrideValueFor(@NotNull EmailProperty @NotNull ...emailProperties) {
		this.propertiesNotToApplyOverrideValueFor = new HashSet<>(asList(emailProperties));
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
		return from(interpretRecipient(fixedName, true, fromAddress, null));
	}
	
	/**
	 * @see EmailPopulatingBuilder#from(String, InternetAddress)
	 */
	@Override
	public EmailPopulatingBuilder from(@Nullable final String fixedName, @NotNull final InternetAddress fromAddress) {
		checkNonEmptyArgument(fromAddress, "fromAddress");
		return from(new Recipient(fixedName, fromAddress.getAddress(), null, null));
	}
	
	/**
	 * @see EmailPopulatingBuilder#from(InternetAddress)
	 */
	@Override
	public EmailPopulatingBuilder from(@NotNull final InternetAddress fromAddress) {
		checkNonEmptyArgument(fromAddress, "fromAddress");
		return from(new Recipient(fromAddress.getPersonal(), fromAddress.getAddress(), null, null));
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
		return withReplyTo(interpretRecipient(null, false, replyToAddress, null));
	}
	
	/**
	 * @see EmailPopulatingBuilder#withReplyTo(String, String)
	 */
	@Override
	public EmailPopulatingBuilder withReplyTo(@Nullable final String fixedName, @NotNull final String replyToAddress) {
		checkNonEmptyArgument(replyToAddress, "replyToAddress");
		return withReplyTo(interpretRecipient(fixedName, true, replyToAddress, null));
	}
	
	/**
	 * @see EmailPopulatingBuilder#withReplyTo(InternetAddress)
	 */
	@Override
	public EmailPopulatingBuilder withReplyTo(@NotNull final InternetAddress replyToAddress) {
		checkNonEmptyArgument(replyToAddress, "replyToAddress");
		return withReplyTo(new Recipient(replyToAddress.getPersonal(), replyToAddress.getAddress(), null, null));
	}
	
	/**
	 * @see EmailPopulatingBuilder#withReplyTo(String, InternetAddress)
	 */
	@Override
	public EmailPopulatingBuilder withReplyTo(@Nullable final String fixedName, @NotNull final InternetAddress replyToAddress) {
		checkNonEmptyArgument(replyToAddress, "replyToAddress");
		return withReplyTo(new Recipient(fixedName, replyToAddress.getAddress(), null, null));
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
		return withBounceTo(bounceToAddress != null ? interpretRecipient(null, false, bounceToAddress, null) : null);
	}
	
	/**
	 * @see EmailPopulatingBuilder#withBounceTo(String, String)
	 */
	@Override
	public EmailPopulatingBuilder withBounceTo(@Nullable final String name, @NotNull final String bounceToAddress) {
		checkNonEmptyArgument(bounceToAddress, "bounceToAddress");
		return withBounceTo(interpretRecipient(name, true, bounceToAddress, null));
	}
	
	/**
	 * @see EmailPopulatingBuilder#withBounceTo(InternetAddress)
	 */
	@Override
	public EmailPopulatingBuilder withBounceTo(@NotNull final InternetAddress bounceToAddress) {
		checkNonEmptyArgument(bounceToAddress, "bounceToAddress");
		return withBounceTo(new Recipient(bounceToAddress.getPersonal(), bounceToAddress.getAddress(), null, null));
	}
	
	/**
	 * @see EmailPopulatingBuilder#withBounceTo(String, InternetAddress)
	 */
	@Override
	@Cli.ExcludeApi(reason = "Method is not detailed enough for CLI")
	public EmailPopulatingBuilder withBounceTo(@Nullable final String name, @NotNull final InternetAddress bounceToAddress) {
		checkNonEmptyArgument(bounceToAddress, "bounceToAddress");
		return withBounceTo(new Recipient(name, bounceToAddress.getAddress(), null, null));
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
		try {
			return withPlainText(FileUtil.readFileContent(textFile));
		} catch (IOException e) {
			throw new EmailException(format(ERROR_READING_FROM_FILE, textFile), e);
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
	 * @see EmailPopulatingBuilder#prependText(File)
	 */
	@Override
	@Cli.OptionNameOverride("prependTextFromFile")
	public EmailPopulatingBuilder prependText(@NotNull final File textFile) {
		try {
			return prependText(FileUtil.readFileContent(textFile));
		} catch (IOException e) {
			throw new EmailException(format(ERROR_READING_FROM_FILE, textFile), e);
		}
	}
	
	/**
	 * @see EmailPopulatingBuilder#prependText(String)
	 */
	@Override
	public EmailPopulatingBuilder prependText(@NotNull final String text) {
		this.text = text + defaultTo(this.text, "");
		return this;
	}
	
	/**
	 * @see EmailPopulatingBuilder#appendText(File)
	 */
	@Override
	@Cli.OptionNameOverride("appendTextFromFile")
	public EmailPopulatingBuilder appendText(@NotNull final File textFile) {
		try {
			return appendText(FileUtil.readFileContent(textFile));
		} catch (IOException e) {
			throw new EmailException(format(ERROR_READING_FROM_FILE, textFile), e);
		}
	}
	
	/**
	 * @see EmailPopulatingBuilder#appendText(String)
	 */
	@Override
	public EmailPopulatingBuilder appendText(@NotNull final String text) {
		this.text = defaultTo(this.text, "") + text;
		return this;
	}
	
	/**
	 * @see EmailPopulatingBuilder#withHTMLText(File)
	 */
	@Override
	@Cli.OptionNameOverride("withHTMLTextFromFile")
	public EmailPopulatingBuilder withHTMLText(@NotNull final File textHTMLFile) {
		try {
			return withHTMLText(FileUtil.readFileContent(textHTMLFile));
		} catch (IOException e) {
			throw new EmailException(format(ERROR_READING_FROM_FILE, textHTMLFile), e);
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
	 * @see EmailPopulatingBuilder#prependTextHTML(File)
	 */
	@Override
	@Cli.OptionNameOverride("prependTextHTMLFromFile")
	public EmailPopulatingBuilder prependTextHTML(@NotNull final File textHTMLFile) {
		try {
			return prependTextHTML(FileUtil.readFileContent(textHTMLFile));
		} catch (IOException e) {
			throw new EmailException(format(ERROR_READING_FROM_FILE, textHTMLFile), e);
		}
	}
	
	/**
	 * @see EmailPopulatingBuilder#prependTextHTML(String)
	 */
	@Override
	public EmailPopulatingBuilder prependTextHTML(@NotNull final String textHTML) {
		this.textHTML = textHTML + defaultTo(this.textHTML, "");
		return this;
	}
	
	/**
	 * @see EmailPopulatingBuilder#appendTextHTML(File)
	 */
	@Override
	@Cli.OptionNameOverride("appendTextHTMLFromFile")
	public EmailPopulatingBuilder appendTextHTML(@NotNull final File textHTMLFile) {
		try {
			return appendTextHTML(FileUtil.readFileContent(textHTMLFile));
		} catch (IOException e) {
			throw new EmailException(format(ERROR_READING_FROM_FILE, textHTMLFile), e);
		}
	}
	
	/**
	 * @see EmailPopulatingBuilder#appendTextHTML(String)
	 */
	@Override
	public EmailPopulatingBuilder appendTextHTML(@NotNull final String textHTML) {
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
	 * @see EmailPopulatingBuilder#withContentTransferEncoding(ContentTransferEncoding)
	 */
	@Override
	public EmailPopulatingBuilder withContentTransferEncoding(@NotNull final ContentTransferEncoding contentTransferEncoding) {
		this.contentTransferEncoding = contentTransferEncoding;
		return this;
	}
	
	/*
		TO: Recipient
	 */
	
	/**
	 * @see EmailPopulatingBuilder#to(Recipient...)
	 */
	@Override
	public EmailPopulatingBuilder to(@NotNull final Recipient @NotNull ... recipients) {
		return withRecipients(asList(recipients), TO);
	}
	
	/**
	 * @see EmailPopulatingBuilder#to(Collection)
	 */
	@Override
	public EmailPopulatingBuilder to(@NotNull final Collection<Recipient> recipients) {
		return withRecipients(recipients, TO);
	}

	/*
		TO: String
	 */
	
	/**
	 * @see EmailPopulatingBuilder#to(String, String)
	 */
	@Override
	public EmailPopulatingBuilder to(@Nullable final String name, String oneOrMoreAddresses) {
		return withRecipients(name, true, singletonList(oneOrMoreAddresses), TO);
	}
	
	/**
	 * @see EmailPopulatingBuilder#to(String)
	 */
	@Override
	@Cli.ExcludeApi(reason = "API is subset of another API method")
	public EmailPopulatingBuilder to(@NotNull final String oneOrMoreAddresses) {
		return withRecipientsWithDefaultName(null, singletonList(oneOrMoreAddresses), TO);
	}
	
	/**
	 * @see EmailPopulatingBuilder#withRecipientsWithFixedName(String, Collection, RecipientType)
	 */
	@Override
	public EmailPopulatingBuilder to(@Nullable final String name, @NotNull final String @NotNull ... oneOrMoreAddressesEach) {
		return withRecipientsWithFixedName(name, asList(oneOrMoreAddressesEach), TO);
	}
	
	/**
	 * @see EmailPopulatingBuilder#withRecipientsWithFixedName(String, Collection, RecipientType)
	 */
	@Override
	public EmailPopulatingBuilder to(@Nullable final String name, @NotNull final Collection<String> oneOrMoreAddressesEach) {
		return withRecipientsWithFixedName(name, oneOrMoreAddressesEach, TO);
	}
	
	/**
	 * @see EmailPopulatingBuilder#toMultiple(String...)
	 */
	@Override
	public EmailPopulatingBuilder toMultiple(@NotNull final String @NotNull ... oneOrMoreAddressesEach) {
		return withRecipientsWithDefaultName(null, asList(oneOrMoreAddressesEach), TO);
	}
	
	/**
	 * @see EmailPopulatingBuilder#toMultiple(Collection)
	 */
	@Override
	public EmailPopulatingBuilder toMultiple(@NotNull final Collection<String> oneOrMoreAddressesEach) {
		return withRecipientsWithDefaultName(null, oneOrMoreAddressesEach, TO);
	}
	
	/**
	 * @see EmailPopulatingBuilder#toWithFixedName(String, String...)
	 */
	@Override
	public EmailPopulatingBuilder toWithFixedName(@Nullable final String name, @NotNull final String @NotNull ... oneOrMoreAddressesEach) {
		return withRecipientsWithFixedName(name, asList(oneOrMoreAddressesEach), TO);
	}
	
	/**
	 * @see EmailPopulatingBuilder#toWithDefaultName(String, String...)
	 */
	@Override
	public EmailPopulatingBuilder toWithDefaultName(@NotNull final String name, @NotNull final String @NotNull ... oneOrMoreAddressesEach) {
		return withRecipientsWithDefaultName(name, asList(oneOrMoreAddressesEach), TO);
	}
	
	/**
	 * @see EmailPopulatingBuilder#toWithFixedName(String, Collection)
	 */
	@Override
	public EmailPopulatingBuilder toWithFixedName(@Nullable final String name, @NotNull final Collection<String> oneOrMoreAddressesEach) {
		return withRecipientsWithFixedName(name, oneOrMoreAddressesEach, TO);
	}
	
	/**
	 * @see EmailPopulatingBuilder#toWithDefaultName(String, Collection)
	 */
	@Override
	public EmailPopulatingBuilder toWithDefaultName(@NotNull final String name, @NotNull final Collection<String> oneOrMoreAddressesEach) {
		return withRecipientsWithDefaultName(name, oneOrMoreAddressesEach, TO);
	}
	
	/*
		TO: InternetAddress
	 */
	
	/**
	 * @see EmailPopulatingBuilder#withRecipientsFromAddressesWithFixedName(String, Collection, RecipientType)
	 */
	@Override
	public EmailPopulatingBuilder to(@Nullable final String name, InternetAddress address) {
		return withRecipientsFromAddressesWithFixedName(name, singletonList(address), TO);
	}
	
	/**
	 * @see EmailPopulatingBuilder#withRecipientsFromAddressesWithDefaultName(String, Collection, RecipientType)
	 */
	@Override
	public EmailPopulatingBuilder to(@NotNull final InternetAddress address) {
		return withRecipientsFromAddressesWithDefaultName(null, singletonList(address), TO);
	}
	
	/**
	 * @see EmailPopulatingBuilder#withRecipientsFromAddressesWithFixedName(String, Collection, RecipientType)
	 */
	@Override
	public EmailPopulatingBuilder to(@Nullable final String name, @NotNull final InternetAddress @NotNull ... adresses) {
		return withRecipientsFromAddressesWithFixedName(name, asList(adresses), TO);
	}
	
	/**
	 * @see EmailPopulatingBuilder#withRecipientsFromAddressesWithFixedName(String, Collection, RecipientType)
	 */
	@Override
	public EmailPopulatingBuilder toAddresses(@Nullable final String name, @NotNull final Collection<InternetAddress> adresses) {
		return withRecipientsFromAddressesWithFixedName(name, adresses, TO);
	}
	
	/**
	 * @see EmailPopulatingBuilder#toMultiple(InternetAddress...)
	 */
	@Override
	public EmailPopulatingBuilder toMultiple(@NotNull final InternetAddress @NotNull ... adresses) {
		return withRecipientsFromAddressesWithDefaultName(null, asList(adresses), TO);
	}
	
	/**
	 * @see EmailPopulatingBuilder#toMultipleAddresses(Collection)
	 */
	@Override
	public EmailPopulatingBuilder toMultipleAddresses(@NotNull final Collection<InternetAddress> adresses) {
		return withRecipientsFromAddressesWithDefaultName(null, adresses, TO);
	}
	
	/**
	 * @see EmailPopulatingBuilder#toAddressesWithFixedName(String, InternetAddress...)
	 */
	@Override
	public EmailPopulatingBuilder toAddressesWithFixedName(@Nullable final String name, @NotNull final InternetAddress @NotNull ... adresses) {
		return withRecipientsFromAddressesWithFixedName(name, asList(adresses), TO);
	}
	
	/**
	 * @see EmailPopulatingBuilder#toAddressesWithDefaultName(String, InternetAddress...)
	 */
	@Override
	public EmailPopulatingBuilder toAddressesWithDefaultName(@NotNull final String name, @NotNull final InternetAddress @NotNull ... adresses) {
		return withRecipientsFromAddressesWithDefaultName(name, asList(adresses), TO);
	}
	
	/**
	 * @see EmailPopulatingBuilder#toAddressesWithFixedName(String, Collection)
	 */
	@Override
	public EmailPopulatingBuilder toAddressesWithFixedName(@Nullable final String name, @NotNull final Collection<InternetAddress> adresses) {
		return withRecipientsFromAddressesWithFixedName(name, adresses, TO);
	}
	
	/**
	 * @see EmailPopulatingBuilder#toAddressesWithDefaultName(String, Collection)
	 */
	@Override
	public EmailPopulatingBuilder toAddressesWithDefaultName(@NotNull final String name, @NotNull final Collection<InternetAddress> adresses) {
		return withRecipientsFromAddressesWithDefaultName(name, adresses, TO);
	}

	/*
		CC: Recipient
	 */
	
	/**
	 * @see EmailPopulatingBuilder#cc(Recipient...)
	 */
	@Override
	public EmailPopulatingBuilder cc(@NotNull final Recipient @NotNull ... recipients) {
		return withRecipients(asList(recipients), CC);
	}
	
	/**
	 * @see EmailPopulatingBuilder#cc(Collection)
	 */
	@Override
	public EmailPopulatingBuilder cc(@NotNull final Collection<Recipient> recipients) {
		return withRecipients(recipients, CC);
	}
	/*
		CC: String
	 */
	
	/**
	 * @see EmailPopulatingBuilder#cc(String, String)
	 */
	@Override
	public EmailPopulatingBuilder cc(@Nullable final String name, String oneOrMoreAddresses) {
		return withRecipients(name, true, singletonList(oneOrMoreAddresses), CC);
	}
	
	/**
	 * @see EmailPopulatingBuilder#cc(String)
	 */
	@Override
	@Cli.ExcludeApi(reason = "API is subset of another API method")
	public EmailPopulatingBuilder cc(@NotNull final String oneOrMoreAddresses) {
		return withRecipientsWithDefaultName(null, singletonList(oneOrMoreAddresses), CC);
	}
	
	/**
	 * @see EmailPopulatingBuilder#cc(String, String...)
	 */
	@Override
	public EmailPopulatingBuilder cc(@Nullable final String name, @NotNull final String @NotNull ... oneOrMoreAddressesEach) {
		return ccWithFixedName(name, oneOrMoreAddressesEach);
	}
	
	/**
	 * @see EmailPopulatingBuilder#cc(String, Collection)
	 */
	@Override
	public EmailPopulatingBuilder cc(@Nullable final String name, @NotNull final Collection<String> oneOrMoreAddressesEach) {
		return ccWithFixedName(name, oneOrMoreAddressesEach);
	}
	
	/**
	 * @see EmailPopulatingBuilder#ccMultiple(String...)
	 */
	@Override
	public EmailPopulatingBuilder ccMultiple(@NotNull final String @NotNull ... oneOrMoreAddressesEach) {
		return withRecipientsWithDefaultName(null, asList(oneOrMoreAddressesEach), CC);
	}
	
	/**
	 * @see EmailPopulatingBuilder#ccAddresses(Collection)
	 */
	@Override
	public EmailPopulatingBuilder ccAddresses(@NotNull final Collection<String> oneOrMoreAddressesEach) {
		return withRecipientsWithDefaultName(null, oneOrMoreAddressesEach, CC);
	}
	
	/**
	 * @see EmailPopulatingBuilder#ccWithFixedName(String, String...)
	 */
	@Override
	public EmailPopulatingBuilder ccWithFixedName(@Nullable final String name, @NotNull final String @NotNull ... oneOrMoreAddressesEach) {
		return withRecipientsWithFixedName(name, asList(oneOrMoreAddressesEach), CC);
	}
	
	/**
	 * @see EmailPopulatingBuilder#ccWithDefaultName(String, String...)
	 */
	@Override
	public EmailPopulatingBuilder ccWithDefaultName(@NotNull final String name, @NotNull final String @NotNull ... oneOrMoreAddressesEach) {
		return withRecipientsWithDefaultName(name, asList(oneOrMoreAddressesEach), CC);
	}
	
	/**
	 * @see EmailPopulatingBuilder#ccWithFixedName(String, Collection)
	 */
	@Override
	public EmailPopulatingBuilder ccWithFixedName(@Nullable final String name, @NotNull final Collection<String> oneOrMoreAddressesEach) {
		return withRecipientsWithFixedName(name, oneOrMoreAddressesEach, CC);
	}
	
	/**
	 * @see EmailPopulatingBuilder#ccWithDefaultName(String, Collection)
	 */
	@Override
	public EmailPopulatingBuilder ccWithDefaultName(@NotNull final String name, @NotNull final Collection<String> oneOrMoreAddressesEach) {
		return withRecipientsWithDefaultName(name, oneOrMoreAddressesEach, CC);
	}
	
	/*
		CC: InternetAddress
	 */
	
	/**
	 * @see EmailPopulatingBuilder#cc(String, InternetAddress)
	 */
	@Override
	public EmailPopulatingBuilder cc(@Nullable final String name, InternetAddress address) {
		return ccAddressesWithFixedName(name, address);
	}
	
	/**
	 * @see EmailPopulatingBuilder#cc(InternetAddress)
	 */
	@Override
	public EmailPopulatingBuilder cc(@NotNull final InternetAddress address) {
		return withRecipientsFromAddressesWithDefaultName(null, singletonList(address), CC);
	}
	
	/**
	 * @see EmailPopulatingBuilder#cc(String, InternetAddress...)
	 */
	@Override
	public EmailPopulatingBuilder cc(@Nullable final String name, @NotNull final InternetAddress @NotNull ... adresses) {
		return ccAddressesWithFixedName(name, adresses);
	}
	
	/**
	 * @see EmailPopulatingBuilder#ccAddresses(String, Collection)
	 */
	@Override
	public EmailPopulatingBuilder ccAddresses(@Nullable final String name, @NotNull final Collection<InternetAddress> adresses) {
		return ccAddressesWithFixedName(name, adresses);
	}
	
	/**
	 * @see EmailPopulatingBuilder#ccMultiple(InternetAddress...)
	 */
	@Override
	public EmailPopulatingBuilder ccMultiple(@NotNull final InternetAddress @NotNull ... adresses) {
		return withRecipientsFromAddressesWithDefaultName(null, asList(adresses), CC);
	}
	
	/**
	 * @see EmailPopulatingBuilder#ccMultipleAddresses(Collection)
	 */
	@Override
	public EmailPopulatingBuilder ccMultipleAddresses(@NotNull final Collection<InternetAddress> adresses) {
		return withRecipientsFromAddressesWithDefaultName(null, adresses, CC);
	}
	
	/**
	 * @see EmailPopulatingBuilder#ccAddressesWithFixedName(String, InternetAddress...)
	 */
	@Override
	public EmailPopulatingBuilder ccAddressesWithFixedName(@Nullable final String name, @NotNull final InternetAddress @NotNull ... adresses) {
		return withRecipientsFromAddressesWithFixedName(name, asList(adresses), CC);
	}
	
	/**
	 * @see EmailPopulatingBuilder#ccAddressesWithDefaultName(String, InternetAddress...)
	 */
	@Override
	public EmailPopulatingBuilder ccAddressesWithDefaultName(@NotNull final String name, @NotNull final InternetAddress @NotNull ... adresses) {
		return withRecipientsFromAddressesWithDefaultName(name, asList(adresses), CC);
	}
	
	/**
	 * @see EmailPopulatingBuilder#ccAddressesWithFixedName(String, Collection)
	 */
	@Override
	public EmailPopulatingBuilder ccAddressesWithFixedName(@Nullable final String name, @NotNull final Collection<InternetAddress> adresses) {
		return withRecipientsFromAddressesWithFixedName(name, adresses, CC);
	}
	
	/**
	 * @see EmailPopulatingBuilder#ccAddressesWithDefaultName(String, Collection)
	 */
	@Override
	public EmailPopulatingBuilder ccAddressesWithDefaultName(@NotNull final String name, @NotNull final Collection<InternetAddress> adresses) {
		return withRecipientsFromAddressesWithDefaultName(name, adresses, CC);
	}
	/*
		BCC: Recipient
	 */
	
	/**
	 * @see EmailPopulatingBuilder#bcc(Recipient...)
	 */
	@Override
	public EmailPopulatingBuilder bcc(@NotNull final Recipient @NotNull ... recipients) {
		return withRecipients(asList(recipients), BCC);
	}
	
	/**
	 * @see EmailPopulatingBuilder#bcc(Collection)
	 */
	@Override
	public EmailPopulatingBuilder bcc(@NotNull final Collection<Recipient> recipients) {
		return withRecipients(recipients, BCC);
	}
	
	/*
		BCC: String
	 */
	
	/**
	 * @see EmailPopulatingBuilder#bcc(String, String)
	 */
	@Override
	public EmailPopulatingBuilder bcc(@Nullable final String name, String oneOrMoreAddresses) {
		return withRecipients(name, true, singletonList(oneOrMoreAddresses), BCC);
	}
	
	/**
	 * @see EmailPopulatingBuilder#bcc(String)
	 */
	@Override
	@Cli.ExcludeApi(reason = "API is subset of another API")
	public EmailPopulatingBuilder bcc(@NotNull final String oneOrMoreAddresses) {
		return withRecipientsWithDefaultName(null, singletonList(oneOrMoreAddresses), BCC);
	}
	
	/**
	 * @see EmailPopulatingBuilder#bcc(String, String...)
	 */
	@Override
	public EmailPopulatingBuilder bcc(@Nullable final String name, @NotNull final String @NotNull ... oneOrMoreAddressesEach) {
		return bccWithFixedName(name, oneOrMoreAddressesEach);
	}
	
	/**
	 * @see EmailPopulatingBuilder#bcc(String, Collection)
	 */
	@Override
	public EmailPopulatingBuilder bcc(@Nullable final String name, @NotNull final Collection<String> oneOrMoreAddressesEach) {
		return bccWithFixedName(name, oneOrMoreAddressesEach);
	}
	
	/**
	 * @see EmailPopulatingBuilder#bccMultiple(String...)
	 */
	@Override
	public EmailPopulatingBuilder bccMultiple(@NotNull final String @NotNull ... oneOrMoreAddressesEach) {
		return withRecipientsWithDefaultName(null, asList(oneOrMoreAddressesEach), BCC);
	}
	
	/**
	 * @see EmailPopulatingBuilder#bccAddresses(Collection)
	 */
	@Override
	public EmailPopulatingBuilder bccAddresses(@NotNull final Collection<String> oneOrMoreAddressesEach) {
		return withRecipientsWithDefaultName(null, oneOrMoreAddressesEach, BCC);
	}
	
	/**
	 * @see EmailPopulatingBuilder#bccWithFixedName(String, String...)
	 */
	@Override
	public EmailPopulatingBuilder bccWithFixedName(@Nullable final String name, @NotNull final String @NotNull ... oneOrMoreAddressesEach) {
		return withRecipientsWithFixedName(name, asList(oneOrMoreAddressesEach), BCC);
	}
	
	/**
	 * @see EmailPopulatingBuilder#bccWithDefaultName(String, String...)
	 */
	@Override
	public EmailPopulatingBuilder bccWithDefaultName(@NotNull final String name, @NotNull final String @NotNull ... oneOrMoreAddressesEach) {
		return withRecipientsWithDefaultName(name, asList(oneOrMoreAddressesEach), BCC);
	}
	
	/**
	 * @see EmailPopulatingBuilder#bccWithFixedName(String, Collection)
	 */
	@Override
	public EmailPopulatingBuilder bccWithFixedName(@Nullable final String name, @NotNull final Collection<String> oneOrMoreAddressesEach) {
		return withRecipientsWithFixedName(name, oneOrMoreAddressesEach, BCC);
	}
	
	/**
	 * @see EmailPopulatingBuilder#bccWithDefaultName(String, Collection)
	 */
	@Override
	public EmailPopulatingBuilder bccWithDefaultName(@NotNull final String name, @NotNull final Collection<String> oneOrMoreAddressesEach) {
		return withRecipientsWithDefaultName(name, oneOrMoreAddressesEach, BCC);
	}
	
	/*
		BCC: InternetAddress
	 */
	
	/**
	 * @see EmailPopulatingBuilder#bcc(String, InternetAddress)
	 */
	@Override
	public EmailPopulatingBuilder bcc(@Nullable final String name, InternetAddress address) {
		return bccAddressesWithFixedName(name, address);
	}
	
	/**
	 * @see EmailPopulatingBuilder#bcc(InternetAddress)
	 */
	@Override
	public EmailPopulatingBuilder bcc(@NotNull final InternetAddress address) {
		return withRecipientsFromAddressesWithDefaultName(null, singletonList(address), BCC);
	}
	
	/**
	 * @see EmailPopulatingBuilder#bcc(String, InternetAddress...)
	 */
	@Override
	public EmailPopulatingBuilder bcc(@Nullable final String name, @NotNull final InternetAddress @NotNull ... adresses) {
		return bccAddressesWithFixedName(name, adresses);
	}
	
	/**
	 * @see EmailPopulatingBuilder#bccAddresses(String, Collection)
	 */
	@Override
	public EmailPopulatingBuilder bccAddresses(@Nullable final String name, @NotNull final Collection<InternetAddress> adresses) {
		return bccAddressesWithFixedName(name, adresses);
	}
	
	/**
	 * @see EmailPopulatingBuilder#bccMultiple(InternetAddress...)
	 */
	@Override
	public EmailPopulatingBuilder bccMultiple(@NotNull final InternetAddress @NotNull ... adresses) {
		return withRecipientsFromAddressesWithDefaultName(null, asList(adresses), BCC);
	}
	
	/**
	 * @see EmailPopulatingBuilder#bccMultipleAddresses(Collection)
	 */
	@Override
	public EmailPopulatingBuilder bccMultipleAddresses(@NotNull final Collection<InternetAddress> adresses) {
		return withRecipientsFromAddressesWithDefaultName(null, adresses, BCC);
	}
	
	/**
	 * @see EmailPopulatingBuilder#bccAddressesWithFixedName(String, InternetAddress...)
	 */
	@Override
	public EmailPopulatingBuilder bccAddressesWithFixedName(@Nullable final String name, @NotNull final InternetAddress @NotNull ... adresses) {
		return withRecipientsFromAddressesWithFixedName(name, asList(adresses), BCC);
	}
	
	/**
	 * @see EmailPopulatingBuilder#bccAddressesWithDefaultName(String, InternetAddress...)
	 */
	@Override
	public EmailPopulatingBuilder bccAddressesWithDefaultName(@NotNull final String name, @NotNull final InternetAddress @NotNull ... adresses) {
		return withRecipientsFromAddressesWithDefaultName(name, asList(adresses), BCC);
	}
	
	/**
	 * @see EmailPopulatingBuilder#bccAddressesWithFixedName(String, Collection)
	 */
	@Override
	public EmailPopulatingBuilder bccAddressesWithFixedName(@Nullable final String name, @NotNull final Collection<InternetAddress> adresses) {
		return withRecipientsFromAddressesWithFixedName(name, adresses, BCC);
	}
	
	/**
	 * @see EmailPopulatingBuilder#bccAddressesWithDefaultName(String, Collection)
	 */
	@Override
	public EmailPopulatingBuilder bccAddressesWithDefaultName(@NotNull final String name, @NotNull final Collection<InternetAddress> adresses) {
		return withRecipientsFromAddressesWithDefaultName(name, adresses, BCC);
	}
	
	/**
	 * @see EmailPopulatingBuilder#withRecipientsWithDefaultName(String, Collection, RecipientType)
	 */
	@Override
	@NotNull
	public EmailPopulatingBuilder withRecipientsWithDefaultName(@Nullable final String defaultName, @NotNull Collection<String> oneOrMoreAddressesEach, @Nullable RecipientType recipientType) {
		return withRecipients(defaultName, false, oneOrMoreAddressesEach, recipientType);
	}
	
	/**
	 * @see EmailPopulatingBuilder#withRecipientsWithFixedName(String, Collection, RecipientType)
	 */
	@Override
	@NotNull
	public EmailPopulatingBuilder withRecipientsWithFixedName(@Nullable final String fixedName, @NotNull Collection<String> oneOrMoreAddressesEach, @Nullable RecipientType recipientType) {
		return withRecipients(fixedName, true, oneOrMoreAddressesEach, recipientType);
	}
	
	/**
	 * @see EmailPopulatingBuilder#withRecipientsWithDefaultName(String, RecipientType, String...)
	 */
	@Override
	@NotNull
	public EmailPopulatingBuilder withRecipientsWithDefaultName(@Nullable String name, @Nullable RecipientType recipientType, @NotNull String @NotNull ... oneOrMoreAddressesEach) {
		return withRecipients(name, false, asList(oneOrMoreAddressesEach), recipientType);
	}
	
	/**
	 * @see EmailPopulatingBuilder#withRecipientsWithFixedName(String, RecipientType, String...)
	 */
	@Override
	@NotNull
	public EmailPopulatingBuilder withRecipientsWithFixedName(@Nullable String name, @Nullable RecipientType recipientType, @NotNull String @NotNull ... oneOrMoreAddressesEach) {
		return withRecipients(name, true, asList(oneOrMoreAddressesEach), recipientType);
	}
	
	/**
	 * @see EmailPopulatingBuilder#withRecipients(String, boolean, RecipientType, String...)
	 */
	@Override
	@NotNull
	public EmailPopulatingBuilder withRecipients(@Nullable String name, boolean fixedName, @Nullable RecipientType recipientType, @NotNull String @NotNull ... oneOrMoreAddressesEach) {
		return withRecipients(name, fixedName, asList(oneOrMoreAddressesEach), recipientType);
	}
	
	/**
	 * @see EmailPopulatingBuilder#withRecipients(String, boolean, Collection, RecipientType)
	 */
	@Override
	@NotNull
	public EmailPopulatingBuilder withRecipients(@Nullable String name, boolean fixedName, @NotNull Collection<String> oneOrMoreAddressesEach, @Nullable RecipientType recipientType) {
		for (String oneOrMoreAddresses : oneOrMoreAddressesEach) {
			for (String emailAddress : extractEmailAddresses(oneOrMoreAddresses)) {
				withRecipient(name, fixedName, emailAddress, recipientType);
			}
		}
		return this;
	}
	
	/**
	 * @see EmailPopulatingBuilder#withRecipientsFromAddressesWithDefaultName(String, Collection, RecipientType)
	 */
	@Override
	@NotNull
	public EmailPopulatingBuilder withRecipientsFromAddressesWithDefaultName(@Nullable final String defaultName, @NotNull Collection<InternetAddress> addresses, @Nullable RecipientType recipientType) {
		return withRecipientsFromAddresses(defaultName, false, addresses, recipientType);
	}
	
	/**
	 * @see EmailPopulatingBuilder#withRecipientsFromAddressesWithFixedName(String, Collection, RecipientType)
	 */
	@Override
	@NotNull
	public EmailPopulatingBuilder withRecipientsFromAddressesWithFixedName(@Nullable final String fixedName, @NotNull Collection<InternetAddress> addresses, @Nullable RecipientType recipientType) {
		return withRecipientsFromAddresses(fixedName, true, addresses, recipientType);
	}
	
	/**
	 * @see EmailPopulatingBuilder#withRecipientsFromAddresses(String, boolean, Collection, RecipientType)
	 */
	@Override
	@NotNull
	public EmailPopulatingBuilder withRecipientsFromAddresses(@Nullable String name, boolean fixedName, @NotNull Collection<InternetAddress> addresses, @Nullable RecipientType recipientType) {
		for (InternetAddress address : addresses) {
			String effectiveName = (fixedName || valueNullOrEmpty(address.getPersonal())) ? name : address.getPersonal();
			withRecipient(effectiveName, address.getAddress(), recipientType);
		}
		return this;
	}
	
	/**
	 * @see EmailPopulatingBuilder#withRecipients(Collection)
	 */
	@Override
	public EmailPopulatingBuilder withRecipients(@NotNull final Collection<Recipient> recipients) {
		return withRecipients(recipients, null);
	}
	
	/**
	 * @see EmailPopulatingBuilder#withRecipients(Recipient...)
	 */
	@Override
	public EmailPopulatingBuilder withRecipients(@NotNull final Recipient @NotNull ... recipients) {
		return withRecipients(asList(recipients), null);
	}
	
	/**
	 * @see EmailPopulatingBuilder#withRecipients(Collection, RecipientType)
	 */
	@Override
	@NotNull
	public EmailPopulatingBuilder withRecipients(@NotNull Collection<Recipient> recipients, @Nullable RecipientType fixedRecipientType) {
		for (Recipient recipient : recipients) {
			withRecipient(recipient.getName(), recipient.getAddress(), defaultTo(fixedRecipientType, recipient.getType()));
		}
		return this;
	}
	
	/**
	 * @see EmailPopulatingBuilder#withRecipient(String, RecipientType)
	 */
	@Override
	public EmailPopulatingBuilder withRecipient(@NotNull final String singleAddress, @Nullable final RecipientType recipientType) {
		return withRecipient(null, singleAddress, recipientType);
	}
	
	/**
	 * @see EmailPopulatingBuilder#withRecipient(String, String, RecipientType)
	 */
	@Override
	public EmailPopulatingBuilder withRecipient(@Nullable final String name, @NotNull final String singleAddress, @Nullable final RecipientType recipientType) {
		return withRecipient(name, true, singleAddress, recipientType);
	}
	
	/**
	 * @see EmailPopulatingBuilder#withRecipient(String, boolean, String, RecipientType)
	 */
	@Override
	public EmailPopulatingBuilder withRecipient(@Nullable final String name, boolean fixedName, @NotNull final String singleAddress, @Nullable final RecipientType recipientType) {
		try {
			recipients.add(MiscUtil.interpretRecipient(name, fixedName, singleAddress, recipientType));
		} catch (Exception e){
			// assume recipient was malformed and simply ignore it
		}
		return this;
	}
	
	/**
	 * @see EmailPopulatingBuilder#withRecipient(Recipient)
	 */
	@Override
	public EmailPopulatingBuilder withRecipient(@NotNull final Recipient recipient) {
		recipients.add(new Recipient(recipient.getName(), recipient.getAddress(), recipient.getType(), null));
		return this;
	}

	/**
	 * @see EmailPopulatingBuilder#withEmbeddedImageBaseDir(String)
	 */
	@Override
	public EmailPopulatingBuilder withEmbeddedImageBaseDir(@NotNull final String embeddedImageBaseDir) {
		this.embeddedImageBaseDir = checkNonEmptyArgument(embeddedImageBaseDir, "embeddedImageBaseDir");
		return this;
	}

	/**
	 * @see EmailPopulatingBuilder#withEmbeddedImageAutoResolutionForFiles(boolean)
	 */
	@Override
	public EmailPopulatingBuilder withEmbeddedImageAutoResolutionForFiles(final boolean embeddedImageAutoResolutionForFiles) {
		this.embeddedImageAutoResolutionForFiles = embeddedImageAutoResolutionForFiles;
		return this;
	}

	/**
	 * @see EmailPopulatingBuilder#withEmbeddedImageAutoResolutionForClassPathResources(boolean)
	 */
	@Override
	public EmailPopulatingBuilder withEmbeddedImageAutoResolutionForClassPathResources(final boolean embeddedImageAutoResolutionForClassPathResources) {
		this.embeddedImageAutoResolutionForClassPathResources = embeddedImageAutoResolutionForClassPathResources;
		return this;
	}

	/**
	 * @see EmailPopulatingBuilder#withEmbeddedImageAutoResolutionForURLs(boolean)
	 */
	@Override
	public EmailPopulatingBuilder withEmbeddedImageAutoResolutionForURLs(final boolean embeddedImageAutoResolutionForURLs) {
		this.embeddedImageAutoResolutionForURLs = embeddedImageAutoResolutionForURLs;
		return this;
	}

	/**
	 * @see EmailPopulatingBuilder#withEmbeddedImageBaseClassPath(String)
	 */
	@Override
	public EmailPopulatingBuilder withEmbeddedImageBaseClassPath(@NotNull final String embeddedImageBaseClassPath) {
		this.embeddedImageBaseClassPath = checkNonEmptyArgument(embeddedImageBaseClassPath, "embeddedImageBaseClassPath");
		return this;
	}

	/**
	 * @see EmailPopulatingBuilder#withEmbeddedImageBaseUrl(String)
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
	 * @see EmailPopulatingBuilder#withEmbeddedImageBaseUrl(URL)
	 */
	@Override
	public EmailPopulatingBuilder withEmbeddedImageBaseUrl(@NotNull final URL embeddedImageBaseUrl) {
		this.embeddedImageBaseUrl = checkNonEmptyArgument(embeddedImageBaseUrl, "embeddedImageBaseUrl");
		return this;
	}

	/**
	 * @see EmailPopulatingBuilder#allowingEmbeddedImageOutsideBaseDir(boolean)
	 */
	@Override
	public EmailPopulatingBuilder allowingEmbeddedImageOutsideBaseDir(final boolean allowEmbeddedImageOutsideBaseDir) {
		this.allowEmbeddedImageOutsideBaseDir = allowEmbeddedImageOutsideBaseDir;
		return this;
	}

	/**
	 * @see EmailPopulatingBuilder#allowingEmbeddedImageOutsideBaseClassPath(boolean)
	 */
	@Override
	public EmailPopulatingBuilder allowingEmbeddedImageOutsideBaseClassPath(final boolean allowEmbeddedImageOutsideBaseClassPath) {
		this.allowEmbeddedImageOutsideBaseClassPath = allowEmbeddedImageOutsideBaseClassPath;
		return this;
	}

	/**
	 * @see EmailPopulatingBuilder#allowingEmbeddedImageOutsideBaseUrl(boolean)
	 */
	@Override
	public EmailPopulatingBuilder allowingEmbeddedImageOutsideBaseUrl(final boolean allowEmbeddedImageOutsideBaseUrl) {
		this.allowEmbeddedImageOutsideBaseUrl = allowEmbeddedImageOutsideBaseUrl;
		return this;
	}

	/**
	 * @see EmailPopulatingBuilder#embeddedImageAutoResolutionMustBeSuccesful(boolean)
	 */
	@Override
	public EmailPopulatingBuilder embeddedImageAutoResolutionMustBeSuccesful(final boolean embeddedImageAutoResolutionMustBeSuccesful) {
		this.embeddedImageAutoResolutionMustBeSuccesful = embeddedImageAutoResolutionMustBeSuccesful;
		return this;
	}

	/**
	 * @see EmailPopulatingBuilder#withEmbeddedImage(String, byte[], String)
	 */
	@Override
	public EmailPopulatingBuilder withEmbeddedImage(@NotNull final String name, final byte@NotNull[] data, @NotNull final String mimetype) {
		checkNonEmptyArgument(name, "name");
		checkNonEmptyArgument(data, "data");
		checkNonEmptyArgument(mimetype, "mimetype");
		
		final ByteArrayDataSource dataSource = new ByteArrayDataSource(data, mimetype);
		dataSource.setName(name);
		return withEmbeddedImage(name, dataSource);
	}
	
	/**
	 * @see EmailPopulatingBuilder#withEmbeddedImage(String, DataSource)
	 */
	@Override
	public EmailPopulatingBuilder withEmbeddedImage(@Nullable final String name, @NotNull final DataSource imagedata) {
		checkNonEmptyArgument(imagedata, "imagedata");
		if (valueNullOrEmpty(name) && valueNullOrEmpty(imagedata.getName())) {
			throw new EmailException(NAME_MISSING_FOR_EMBEDDED_IMAGE);
		}
		embeddedImages.add(new AttachmentResource(name, imagedata, null));
		return this;
	}
	
	/**
	 * @see EmailPopulatingBuilder#withEmbeddedImages(List)
	 */
	@Override
	public EmailPopulatingBuilder withEmbeddedImages(@NotNull final List<AttachmentResource> embeddedImages) {
		for (final AttachmentResource embeddedImage : embeddedImages) {
			withEmbeddedImage(embeddedImage.getName(), embeddedImage.getDataSource());
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

	/**
	 * @see EmailPopulatingBuilder#withAttachment(String, byte[], String)
	 */
	@Override
	public EmailPopulatingBuilder withAttachment(@Nullable final String name, final byte@NotNull[] data, @NotNull final String mimetype) {
		return withAttachment(name, data, mimetype, null, null);
	}

	/**
	 * @see EmailPopulatingBuilder#withAttachment(String, byte[], String, String)
	 */
	@Override
	public EmailPopulatingBuilder withAttachment(@Nullable final String name, final byte@NotNull[] data, @NotNull final String mimetype, @Nullable final String description) {
		return withAttachment(name, data, mimetype, description, null);
	}

	/**
	 * @see EmailPopulatingBuilder#withAttachment(String, byte[], String, String, ContentTransferEncoding)
	 */
	@Override
	public EmailPopulatingBuilder withAttachment(@Nullable final String name, final byte@NotNull[] data, @NotNull final String mimetype, @Nullable final String description, @Nullable final ContentTransferEncoding contentTransferEncoding) {
		requireNonNull(data, "data");
		checkNonEmptyArgument(mimetype, "mimetype");
		final ByteArrayDataSource dataSource = new ByteArrayDataSource(data, mimetype);
		dataSource.setName(name);
		withAttachment(name, dataSource, description, contentTransferEncoding);
		return this;
	}

	/**
	 * @see EmailPopulatingBuilder#withAttachment(String, DataSource)
	 */
	@Override
	public EmailPopulatingBuilder withAttachment(@Nullable final String name, @NotNull final DataSource filedata) {
		return withAttachment(name, filedata, null, null);
	}

	/**
	 * @see EmailPopulatingBuilder#withAttachment(String, DataSource, String)
	 */
	@Override
	public EmailPopulatingBuilder withAttachment(@Nullable final String name, @NotNull final DataSource filedata, @Nullable final String description) {
		return withAttachment(name, filedata, description, null);
	}

	/**
	 * @see EmailPopulatingBuilder#withAttachment(String, DataSource, String, ContentTransferEncoding)
	 */
	@Override
	public EmailPopulatingBuilder withAttachment(@Nullable final String name, @NotNull final DataSource filedata, @Nullable final String description, @Nullable final ContentTransferEncoding contentTransferEncoding) {
		checkNonEmptyArgument(filedata, "filedata");
		attachments.add(new AttachmentResource(name, filedata, description, contentTransferEncoding));
		return this;
	}

	/**
	 * @see EmailPopulatingBuilder#withAttachments(List)
	 */
	@Override
	public EmailPopulatingBuilder withAttachments(@NotNull final List<AttachmentResource> attachments) {
		for (final AttachmentResource attachment : attachments) {
			withAttachment(attachment.getName(), attachment.getDataSource(), attachment.getDescription(), attachment.getContentTransferEncoding());
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
	 * @see EmailPopulatingBuilder#signWithDomainKey(byte[], String, String, Set)
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
	 * @see EmailPopulatingBuilder#signWithSmime(File, String, String, String, String)
	 */
	@Override
	@SuppressFBWarnings(value = "OBL_UNSATISFIED_OBLIGATION", justification = "Input stream being created should not be closed here")
	public EmailPopulatingBuilder signWithSmime(@NotNull final File pkcs12StoreFile, @NotNull final String storePassword, @NotNull final String keyAlias, @NotNull final String keyPassword, @Nullable final String signatureAlgorithm) {
		return signWithSmime(SmimeSigningConfig.builder()
				.pkcs12Config(pkcs12StoreFile, storePassword, keyAlias, keyPassword)
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
	 * @see EmailPopulatingBuilder#encryptWithSmime(File, String, String)
	 */
	@Override
	@SuppressFBWarnings(value = "OBL_UNSATISFIED_OBLIGATION", justification = "Input stream being created should not be closed here")
	public EmailPopulatingBuilder encryptWithSmime(@NotNull final File pemFile, @Nullable final String keyEncapsulationAlgorithm, @Nullable final String cipherAlgorithm) {
		return encryptWithSmime(SmimeEncryptionConfig.builder()
				.x509Certificate(pemFile)
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

	/**
	 * @see EmailPopulatingBuilder#withDispositionNotificationTo()
	 */
	@Override
	@Cli.OptionNameOverride("withDispositionNotificationToEnabled")
	public EmailPopulatingBuilder withDispositionNotificationTo() {
		this.useDispositionNotificationTo = true;
		this.dispositionNotificationTo = null;
		return this;
	}
	
	/**
	 * @see EmailPopulatingBuilder#withDispositionNotificationTo(String)
	 */
	@Override
	@Cli.ExcludeApi(reason = "API is subset of another API")
	public EmailPopulatingBuilder withDispositionNotificationTo(@NotNull final String address) {
		checkNonEmptyArgument(address, "dispositionNotificationToAddress");
		return withDispositionNotificationTo(interpretRecipient(null, false, address, null));
	}
	
	/**
	 * @see EmailPopulatingBuilder#withDispositionNotificationTo(String, String)
	 */
	@Override
	public EmailPopulatingBuilder withDispositionNotificationTo(@Nullable final String name, @NotNull final String address) {
		checkNonEmptyArgument(address, "dispositionNotificationToAddress");
		return withDispositionNotificationTo(interpretRecipient(name, true, address, null));
	}
	
	/**
	 * @see EmailPopulatingBuilder#withDispositionNotificationTo(InternetAddress)
	 */
	@Override
	public EmailPopulatingBuilder withDispositionNotificationTo(@NotNull final InternetAddress address) {
		checkNonEmptyArgument(address, "dispositionNotificationToAddress");
		return withDispositionNotificationTo(new Recipient(address.getPersonal(), address.getAddress(), null, null));
	}
	
	/**
	 * @see EmailPopulatingBuilder#withDispositionNotificationTo(String, InternetAddress)
	 */
	@Override
	public EmailPopulatingBuilder withDispositionNotificationTo(@Nullable final String fixedName, @NotNull final InternetAddress address) {
		checkNonEmptyArgument(address, "dispositionNotificationToAddress");
		return withDispositionNotificationTo(new Recipient(fixedName, address.getAddress(), null, null));
	}
	
	/**
	 * @see EmailPopulatingBuilder#withDispositionNotificationTo(Recipient)
	 */
	@Override
	public EmailPopulatingBuilder withDispositionNotificationTo(@NotNull final Recipient recipient) {
		checkNonEmptyArgument(recipient.getAddress(), "recipient.address");
		this.useDispositionNotificationTo = true;
		this.dispositionNotificationTo = new Recipient(recipient.getName(), recipient.getAddress(), null, null);
		return this;
	}
	
	/**
	 * @see EmailPopulatingBuilder#withReturnReceiptTo()
	 */
	@Override
	@Cli.OptionNameOverride("withReturnReceiptToEnabled")
	public EmailPopulatingBuilder withReturnReceiptTo() {
		this.useReturnReceiptTo = true;
		this.returnReceiptTo = null;
		return this;
	}
	
	/**
	 * @see EmailPopulatingBuilder#withReturnReceiptTo(String)
	 */
	@Override
	@Cli.ExcludeApi(reason = "API is subset of another API")
	public EmailPopulatingBuilder withReturnReceiptTo(@NotNull final String address) {
		checkNonEmptyArgument(address, "address");
		return withReturnReceiptTo(interpretRecipient(null, false, address, null));
	}
	
	/**
	 * @see EmailPopulatingBuilder#withReturnReceiptTo(String, String)
	 */
	@Override
	public EmailPopulatingBuilder withReturnReceiptTo(@Nullable final String name, @NotNull final String address) {
		checkNonEmptyArgument(address, "address");
		return withReturnReceiptTo(interpretRecipient(name, true, address, null));
	}
	
	/**
	 * @see EmailPopulatingBuilder#withReturnReceiptTo(InternetAddress)
	 */
	@Override
	public EmailPopulatingBuilder withReturnReceiptTo(@NotNull final InternetAddress address) {
		checkNonEmptyArgument(address, "address");
		return withReturnReceiptTo(new Recipient(address.getPersonal(), address.getAddress(), null, null));
	}
	
	/**
	 * @see EmailPopulatingBuilder#withReturnReceiptTo(String, InternetAddress)
	 */
	@Override
	public EmailPopulatingBuilder withReturnReceiptTo(@Nullable final String fixedName, @NotNull final InternetAddress address) {
		checkNonEmptyArgument(address, "address");
		return withReturnReceiptTo(new Recipient(fixedName, address.getAddress(), null, null));
	}
	
	/**
	 * @see EmailPopulatingBuilder#withReturnReceiptTo(Recipient)
	 */
	@Override
	public EmailPopulatingBuilder withReturnReceiptTo(@NotNull final Recipient recipient) {
		checkNonEmptyArgument(recipient.getAddress(), "recipient.address");
		this.useReturnReceiptTo = true;
		this.returnReceiptTo = new Recipient(recipient.getName(), recipient.getAddress(), null, null);
		return this;
	}

	/**
	 * @see EmailPopulatingBuilder#withReturnReceiptTo(Recipient)
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
	 * @see EmailPopulatingBuilder#clearContentTransferEncoding()
	 */
	@Override
	public EmailPopulatingBuilder clearContentTransferEncoding() {
		this.contentTransferEncoding = ContentTransferEncoding.QUOTED_PRINTABLE;
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
	 * @see EmailPopulatingBuilder#clearEmbeddedImageBaseDir()
	 */
	public EmailPopulatingBuilder clearEmbeddedImageBaseDir() {
		this.embeddedImageBaseDir = null;
		return this;
	}

	/**
	 * @see EmailPopulatingBuilder#clearEmbeddedImageBaseClassPath()
	 */
	public EmailPopulatingBuilder clearEmbeddedImageBaseClassPath() {
		this.embeddedImageBaseClassPath = null;
		return this;
	}

	/**
	 * @see EmailPopulatingBuilder#clearEmbeddedImageBaseUrl()
	 */
	public EmailPopulatingBuilder clearEmbeddedImageBaseUrl() {
		this.embeddedImageBaseUrl = null;
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
		this.smimeSigningConfig = null;
		this.smimeEncryptionConfig = null;
		return this;
	}

	/**
	 * @see EmailPopulatingBuilder#clearDispositionNotificationTo()
	 */
	@Override
	public EmailPopulatingBuilder clearDispositionNotificationTo() {
		this.useDispositionNotificationTo = false;
		this.dispositionNotificationTo = null;
		return this;
	}
	
	/**
	 * @see EmailPopulatingBuilder#clearReturnReceiptTo()
	 */
	@Override
	public EmailPopulatingBuilder clearReturnReceiptTo() {
		this.useReturnReceiptTo = false;
		this.returnReceiptTo = null;
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
	 * @see EmailPopulatingBuilder#getPropertiesNotToApplyDefaultValueFor()
	 */
	@Override
	@Nullable
	public Set<EmailProperty> getPropertiesNotToApplyDefaultValueFor() {
		return propertiesNotToApplyDefaultValueFor;
	}

	/**
	 * @see EmailPopulatingBuilder#getPropertiesNotToApplyOverrideValueFor()
	 */
	@Override
	@Nullable
	public Set<EmailProperty> getPropertiesNotToApplyOverrideValueFor() {
		return propertiesNotToApplyOverrideValueFor;
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
		return replyToRecipients;
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
	 * @see EmailPopulatingBuilder#getText()
	 */
	@Override
	@Nullable
	public String getText() {
		return text;
	}
	
	/**
	 * @see EmailPopulatingBuilder#getTextHTML()
	 */
	@Override
	@Nullable
	public String getTextHTML() {
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
	 * @see EmailPopulatingBuilder#getTextCalendar()
	 */
	@Override
	@Nullable
	public String getTextCalendar() {
		return textCalendar;
	}

	/**
	 * @see EmailPopulatingBuilder#getContentTransferEncoding()
	 */
	@Override
	@Nullable
	public ContentTransferEncoding getContentTransferEncoding() {
		return contentTransferEncoding;
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
		return decryptedAttachments;
	}

	/**
	 * @see EmailPopulatingBuilder#getHeaders()
	 */
	@NotNull
	@Override
	public Map<String, Collection<String>> getHeaders() {
		return new HashMap<>(headers);
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
	 * @see EmailPopulatingBuilder#getDispositionNotificationTo()
	 */
	@Override
	@Nullable
	public Recipient getDispositionNotificationTo() {
		return dispositionNotificationTo;
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
	 * @see EmailPopulatingBuilder#getReturnReceiptTo()
	 */
	@Override
	@Nullable
	public Recipient getReturnReceiptTo() {
		return returnReceiptTo;
	}

	/**
	 * @see EmailPopulatingBuilder#getOverrideReceivers()
	 */
	@Override
	@NotNull
	public List<Recipient> getOverrideReceivers() {
		return overrideReceivers;
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

	/**
	 * @see EmailPopulatingBuilder#fixingSentDate(Date)
	 */
	@Override
	@Nullable
	public Date getSentDate() {
		return sentDate != null ? new Date(sentDate.getTime()) : null;
	}
}
