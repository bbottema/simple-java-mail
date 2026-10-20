package org.simplejavamail.converter.internal;

import jakarta.mail.MessagingException;
import jakarta.mail.internet.MimeMessage;
import org.jetbrains.annotations.ApiStatus;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.simplejavamail.api.email.Email;
import org.simplejavamail.api.email.DeliveryStatusReport;
import org.simplejavamail.api.email.EmailPopulatingBuilder;
import org.simplejavamail.api.email.ExactEmailBuilder;
import org.simplejavamail.api.email.Recipient;
import org.simplejavamail.api.email.config.DeliveryStatusNotification.NotifyOption;
import org.simplejavamail.api.email.config.DeliveryStatusNotification.ReturnOption;
import org.simplejavamail.api.email.config.DeliveryStatusNotification;
import org.simplejavamail.api.email.config.SmtpDeliveryOptions;
import org.simplejavamail.config.SimpleJavaMailConfig;
import org.simplejavamail.converter.internal.mimemessage.MimeMessageParser;
import org.simplejavamail.email.internal.EmailStartingBuilderImpl;
import org.simplejavamail.email.internal.ExactEmlValidator;
import org.simplejavamail.email.internal.InternalEmail;
import org.simplejavamail.internal.util.FinalizedMimeMessage;
import org.simplejavamail.internal.util.DsnNotifyOptions;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Properties;
import java.util.Set;
import org.simplejavamail.internal.util.JakartaMailImplementation;

import static java.util.Arrays.asList;
import static java.util.Collections.emptySet;
import static java.util.Objects.requireNonNull;
import static org.simplejavamail.internal.util.Preconditions.checkNonEmptyArgument;

/**
 * @see ExactEmailBuilder
 */
@ApiStatus.Internal
public final class ExactEmailBuilderImpl implements ExactEmailBuilder, org.simplejavamail.api.internal.clisupport.CliExactEmailConfigurationBuilder {

	private final SimpleJavaMailConfig config;
	private final byte[] emlBytes;
	private final List<Recipient> envelopeRecipients = new ArrayList<>();
	private Set<NotifyOption> notificationEvents = emptySet();
	@Nullable
	private ReturnOption notificationReturnOption;
	@Nullable
	private String envelopeId;
	@Nullable
	private Recipient envelopeSender;
	private boolean notificationOptionsConfigured;
	private boolean tlsRequiredForOnwardDelivery;
	@Nullable
	private Boolean sendingToAcceptedRecipients;
	@Nullable
	private final DeliveryStatusReport deliveryStatusReport;


    /** @see ExactEmailBuilder#withSmtpDeliveryOptions(SmtpDeliveryOptions) */
    @Override public ExactEmailBuilder withSmtpDeliveryOptions(@NotNull final SmtpDeliveryOptions choices) {
        requireNonNull(choices, "config");
        clearDeliveryStatusNotification();
        if (choices.getDeliveryStatusNotification() != null) withDeliveryStatusNotification(choices.getDeliveryStatusNotification());
        tlsRequiredForOnwardDelivery = choices.isTlsRequiredForOnwardDelivery();
        sendingToAcceptedRecipients = choices.getSendingToAcceptedRecipients();
        return this;
    }
    /** @see ExactEmailBuilder#withSmtpDeliveryOptions(SmtpDeliveryOptions) */
    @Override @NotNull public SmtpDeliveryOptions getSmtpDeliveryOptions() {
        final SmtpDeliveryOptions.Builder choices = SmtpDeliveryOptions.builder().withDeliveryStatusNotification(getDeliveryStatusNotification());
        if (tlsRequiredForOnwardDelivery) choices.withTlsRequiredForOnwardDelivery();
        if (sendingToAcceptedRecipients != null) choices.withSendingToAcceptedRecipients(sendingToAcceptedRecipients);
        return choices.build();
    }
    /** @see ExactEmailBuilder#withSmtpDeliveryOptions(SmtpDeliveryOptions) */
    @Override public ExactEmailBuilder useConfiguredSmtpDeliveryOptions() { return withSmtpDeliveryOptions(SmtpDeliveryOptions.builder().build()); }


	public ExactEmailBuilderImpl(@NotNull final SimpleJavaMailConfig config, final byte @NotNull [] emlBytes) {
		this.config = requireNonNull(config, "config");
		this.emlBytes = ExactEmlValidator.copyAndValidateEml(emlBytes);
		deliveryStatusReport = parseEmlIntoEmailBuilder().getDeliveryStatusReport();
	}

	/**
	 * @see ExactEmailBuilder#withEnvelopeRecipients(String...)
	 */
	@Override
	public ExactEmailBuilder withEnvelopeRecipients(@NotNull final String @NotNull ... recipientAddresses) {
		return withEnvelopeRecipients(asList(checkNonEmptyArgument(recipientAddresses, "recipientAddresses")));
	}

	/**
	 * @see ExactEmailBuilder#withEnvelopeRecipients(Collection)
	 */
	@Override
	public ExactEmailBuilder withEnvelopeRecipients(@NotNull final Collection<String> recipientAddresses) {
		checkNonEmptyArgument(recipientAddresses, "recipientAddresses");
		for (final String recipientAddress : recipientAddresses) {
			envelopeRecipients.add(ExactEmlValidator.parseMailbox(recipientAddress, "envelopeRecipient"));
		}
		return this;
	}

	/** @see ExactEmailBuilder#withEnvelopeRecipients(Recipient...) */
	@Override
	public ExactEmailBuilder withEnvelopeRecipients(@NotNull final Recipient @NotNull ... recipients) {
		checkNonEmptyArgument(recipients, "recipients");
		for (final Recipient recipient : recipients) {
			final Recipient mailbox = ExactEmlValidator.parseMailbox(requireNonNull(recipient, "recipient").getAddress(), "envelopeRecipient");
			envelopeRecipients.add(new Recipient(mailbox.getName(), mailbox.getAddress(), null, null,
					recipient.getDeliveryStatusNotificationNotifyOptions()));
		}
		return this;
	}

	/** @see ExactEmailBuilder#clearEnvelopeRecipients() */
	@Override
	public ExactEmailBuilder clearEnvelopeRecipients() {
		envelopeRecipients.clear();
		return this;
	}

	/** @see ExactEmailBuilder#getEnvelopeRecipients() */
	@Override
	@NotNull
	public List<Recipient> getEnvelopeRecipients() {
		return new ArrayList<>(envelopeRecipients);
	}

	/**
	 * @see ExactEmailBuilder#withEnvelopeSender(String)
	 */
	@Override
	public ExactEmailBuilder withEnvelopeSender(@NotNull final String senderAddress) {
		envelopeSender = ExactEmlValidator.parseMailbox(senderAddress, "envelopeSender");
		return this;
	}

	/** @see ExactEmailBuilder#clearEnvelopeSender() */
	@Override
	public ExactEmailBuilder clearEnvelopeSender() {
		envelopeSender = null;
		return this;
	}

	/** @see ExactEmailBuilder#getEnvelopeSender() */
	@Override
	@Nullable
	public Recipient getEnvelopeSender() {
		return envelopeSender;
	}

	/**
	 * @see org.simplejavamail.api.email.config.SmtpDeliveryOptions.Builder#withDeliveryStatusNotification(DeliveryStatusNotification)
	 */
	@Override
	public ExactEmailBuilder withDeliveryStatusNotification(@NotNull final DeliveryStatusNotification deliveryStatusNotification) {
		requireNonNull(deliveryStatusNotification, "deliveryStatusNotification");
		notificationEvents = deliveryStatusNotification.getNotifyOptions();
		notificationReturnOption = deliveryStatusNotification.getReturnOption();
		envelopeId = deliveryStatusNotification.getEnvelopeId();
		notificationOptionsConfigured = hasSharedNotificationPreference();
		return this;
	}

	/** @see org.simplejavamail.api.email.config.SmtpDeliveryOptions.Builder#clearDeliveryStatusNotification() */
	@Override
	public ExactEmailBuilder clearDeliveryStatusNotification() {
		notificationEvents = emptySet();
		notificationReturnOption = null;
		envelopeId = null;
		notificationOptionsConfigured = false;
		return this;
	}

	/** @see org.simplejavamail.api.email.config.SmtpDeliveryOptions.Builder#getDeliveryStatusNotification() */
	@Override
	@Nullable
	public DeliveryStatusNotification getDeliveryStatusNotification() {
		if (!notificationOptionsConfigured && envelopeId == null) {
			return null;
		}
		return DeliveryStatusNotification.builder()
				.notifyOptions(notificationEvents.toArray(new NotifyOption[0]))
				.returnOption(notificationReturnOption)
				.envelopeId(envelopeId)
				.build();
	}

	/**
	 * @see org.simplejavamail.api.email.config.SmtpDeliveryOptions.Builder#withDeliveryStatusNotificationNotifyOptions(NotifyOption...)
	 */
	@Override
	public ExactEmailBuilder withDeliveryStatusNotificationNotifyOptions(@NotNull final NotifyOption @NotNull ...notifyOptions) {
		notificationEvents = DsnNotifyOptions.copyOf(asList(notifyOptions));
		notificationOptionsConfigured = true;
		return this;
	}

	/** @see org.simplejavamail.api.email.config.SmtpDeliveryOptions.Builder#clearDeliveryStatusNotificationNotifyOptions() */
	@Override
	public ExactEmailBuilder clearDeliveryStatusNotificationNotifyOptions() {
		notificationEvents = emptySet();
		notificationOptionsConfigured = hasSharedNotificationPreference();
		return this;
	}

	/**
	 * @see org.simplejavamail.api.email.config.SmtpDeliveryOptions.Builder#withDeliveryStatusNotificationReturnOption(ReturnOption)
	 */
	@Override
	public ExactEmailBuilder withDeliveryStatusNotificationReturnOption(@NotNull final ReturnOption returnOption) {
		notificationReturnOption = requireNonNull(returnOption, "returnOption");
		notificationOptionsConfigured = true;
		return this;
	}

	/** @see org.simplejavamail.api.email.config.SmtpDeliveryOptions.Builder#clearDeliveryStatusNotificationReturnOption() */
	@Override
	public ExactEmailBuilder clearDeliveryStatusNotificationReturnOption() {
		notificationReturnOption = null;
		notificationOptionsConfigured = hasSharedNotificationPreference();
		return this;
	}

	private boolean hasSharedNotificationPreference() {
		return notificationReturnOption != null || !notificationEvents.isEmpty();
	}

	/**
	 * @see org.simplejavamail.api.email.config.SmtpDeliveryOptions.Builder#fixingEnvelopeId(String)
	 */
	@Override
	public ExactEmailBuilder fixingEnvelopeId(@Nullable final String envelopeId) {
		this.envelopeId = envelopeId;
		return this;
	}

	/** @see org.simplejavamail.api.email.config.SmtpDeliveryOptions.Builder#clearEnvelopeId() */
	@Override
	public ExactEmailBuilder clearEnvelopeId() {
		envelopeId = null;
		notificationOptionsConfigured = hasSharedNotificationPreference();
		return this;
	}

	/** @see org.simplejavamail.api.email.config.SmtpDeliveryOptions.Builder#withTlsRequiredForOnwardDelivery() */
	@Override
	public ExactEmailBuilder withTlsRequiredForOnwardDelivery() {
		tlsRequiredForOnwardDelivery = true;
		return this;
	}

	/** @see org.simplejavamail.api.email.config.SmtpDeliveryOptions.Builder#clearTlsRequiredForOnwardDelivery() */
	@Override
	public ExactEmailBuilder clearTlsRequiredForOnwardDelivery() {
		tlsRequiredForOnwardDelivery = false;
		return this;
	}

	/** @see org.simplejavamail.api.email.config.SmtpDeliveryOptions.Builder#isTlsRequiredForOnwardDelivery() */
	@Override
	public boolean isTlsRequiredForOnwardDelivery() {
		return tlsRequiredForOnwardDelivery;
	}

	/** @see org.simplejavamail.api.email.config.SmtpDeliveryOptions.Builder#withSendingToAcceptedRecipients(boolean) */
	@Override
	public ExactEmailBuilder withSendingToAcceptedRecipients(final boolean sendingToAcceptedRecipients) {
		this.sendingToAcceptedRecipients = sendingToAcceptedRecipients;
		return this;
	}

	/** @see org.simplejavamail.api.email.config.SmtpDeliveryOptions.Builder#clearSendingToAcceptedRecipients() */
	@Override
	public ExactEmailBuilder clearSendingToAcceptedRecipients() {
		sendingToAcceptedRecipients = null;
		return this;
	}

	/** @see org.simplejavamail.api.email.config.SmtpDeliveryOptions.Builder#getSendingToAcceptedRecipients() */
	@Override
	@Nullable
	public Boolean getSendingToAcceptedRecipients() {
		return sendingToAcceptedRecipients;
	}

	/**
	 * @see ExactEmailBuilder#buildEmail()
	 */
	@Override
	public Email buildEmail() {
		checkNonEmptyArgument(envelopeRecipients, "envelopeRecipients");
		final EmailPopulatingBuilder parsedEmailBuilder = parseEmlIntoEmailBuilder()
				.withOverrideReceivers(envelopeRecipients);
		parsedEmailBuilder.clearBounceTo();
		if (envelopeSender != null) {
			parsedEmailBuilder.withBounceTo(envelopeSender);
		}
		parsedEmailBuilder.withSmtpDeliveryOptions(getSmtpDeliveryOptions());
		return new InternalEmail(parsedEmailBuilder, emlBytes);
	}

	/** @see ExactEmailBuilder#getDeliveryStatusReport() */
	@Override
	@Nullable
	public DeliveryStatusReport getDeliveryStatusReport() {
		return deliveryStatusReport;
	}

	@NotNull
	private EmailPopulatingBuilder parseEmlIntoEmailBuilder() {
		try {
			final MimeMessage mimeMessage = FinalizedMimeMessage.fromExactMessageBytes(JakartaMailImplementation.createSession(new Properties()), emlBytes);
			return MimeMessageEmailMapper.populate(
					new EmailStartingBuilderImpl(config).startingBlank(),
					MimeMessageParser.parseMimeMessage(mimeMessage, true));
		} catch (final MessagingException | RuntimeException invalidEml) {
			throw new EmailConverterException("Unable to parse exact EML", invalidEml);
		}
	}
}
