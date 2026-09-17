package org.simplejavamail.converter;

import jakarta.mail.MessagingException;
import jakarta.mail.internet.MimeMessage;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.simplejavamail.api.email.Email;
import org.simplejavamail.api.email.EmailPopulatingBuilder;
import org.simplejavamail.api.email.ExactEmailBuilder;
import org.simplejavamail.api.email.Recipient;
import org.simplejavamail.api.email.config.DeliveryStatusNotification;
import org.simplejavamail.api.email.config.DeliveryStatusNotification.DeliveryStatusNotificationBuilder;
import org.simplejavamail.api.email.config.DeliveryStatusNotification.NotifyOption;
import org.simplejavamail.api.email.config.DeliveryStatusNotification.ReturnOption;
import org.simplejavamail.config.SimpleJavaMailConfig;
import org.simplejavamail.converter.internal.mimemessage.MimeMessageParser;
import org.simplejavamail.email.internal.EmailStartingBuilderImpl;
import org.simplejavamail.email.internal.ExactEmlValidator;
import org.simplejavamail.email.internal.InternalEmail;
import org.simplejavamail.internal.util.FinalizedMimeMessage;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;

import static java.util.Arrays.asList;
import static java.util.Objects.requireNonNull;
import static org.simplejavamail.internal.util.Preconditions.checkNonEmptyArgument;

/**
 * @see ExactEmailBuilder
 */
public final class ExactEmailBuilderImpl implements ExactEmailBuilder {

	private final SimpleJavaMailConfig config;
	private final byte[] emlBytes;
	private final List<Recipient> envelopeRecipients = new ArrayList<>();
	private DeliveryStatusNotificationBuilder notificationBuilder = DeliveryStatusNotification.builder();
	@Nullable
	private Recipient envelopeSender;
	private boolean notificationOptionsConfigured;
	private boolean envelopeIdentifierFixed;
	private boolean tlsRequiredForOnwardDelivery;

	public ExactEmailBuilderImpl(@NotNull final SimpleJavaMailConfig config, final byte @NotNull [] emlBytes) {
		this.config = requireNonNull(config, "config");
		this.emlBytes = ExactEmlValidator.copyAndValidateEml(emlBytes);
		verifyEmlCanBeParsed();
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

	/**
	 * @see ExactEmailBuilder#withEnvelopeSender(String)
	 */
	@Override
	public ExactEmailBuilder withEnvelopeSender(@NotNull final String senderAddress) {
		envelopeSender = ExactEmlValidator.parseMailbox(senderAddress, "envelopeSender");
		return this;
	}

	/**
	 * @see ExactEmailBuilder#withDeliveryStatusNotification(DeliveryStatusNotification)
	 */
	@Override
	public ExactEmailBuilder withDeliveryStatusNotification(@NotNull final DeliveryStatusNotification deliveryStatusNotification) {
		notificationBuilder = requireNonNull(deliveryStatusNotification, "deliveryStatusNotification").toBuilder();
		notificationOptionsConfigured = deliveryStatusNotification.getReturnOption() != null || !deliveryStatusNotification.getNotifyOptions().isEmpty();
		envelopeIdentifierFixed = deliveryStatusNotification.getEnvelopeId() != null;
		return this;
	}

	/**
	 * @see ExactEmailBuilder#withDeliveryStatusNotificationNotifyOptions(NotifyOption...)
	 */
	@Override
	public ExactEmailBuilder withDeliveryStatusNotificationNotifyOptions(@NotNull final NotifyOption @NotNull ...notifyOptions) {
		notificationBuilder.notifyOptions(notifyOptions);
		notificationOptionsConfigured = true;
		return this;
	}

	/**
	 * @see ExactEmailBuilder#withDeliveryStatusNotificationReturnOption(ReturnOption)
	 */
	@Override
	public ExactEmailBuilder withDeliveryStatusNotificationReturnOption(@NotNull final ReturnOption returnOption) {
		notificationBuilder.returnOption(requireNonNull(returnOption, "returnOption"));
		notificationOptionsConfigured = true;
		return this;
	}

	/**
	 * @see ExactEmailBuilder#fixingEnvelopeId(String)
	 */
	@Override
	public ExactEmailBuilder fixingEnvelopeId(@Nullable final String envelopeId) {
		notificationBuilder.envelopeId(envelopeId);
		envelopeIdentifierFixed = envelopeId != null;
		return this;
	}

	/** @see ExactEmailBuilder#withTlsRequiredForOnwardDelivery() */
	@Override
	public ExactEmailBuilder withTlsRequiredForOnwardDelivery() {
		tlsRequiredForOnwardDelivery = true;
		return this;
	}

	/** @see ExactEmailBuilder#clearTlsRequiredForOnwardDelivery() */
	@Override
	public ExactEmailBuilder clearTlsRequiredForOnwardDelivery() {
		tlsRequiredForOnwardDelivery = false;
		return this;
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
		if (notificationOptionsConfigured || envelopeIdentifierFixed) {
			parsedEmailBuilder.withDeliveryStatusNotification(notificationBuilder.build());
		}
		if (tlsRequiredForOnwardDelivery) {
			parsedEmailBuilder.withTlsRequiredForOnwardDelivery();
		}
		return new InternalEmail(parsedEmailBuilder, emlBytes);
	}

	private void verifyEmlCanBeParsed() {
		parseEmlIntoEmailBuilder();
	}

	@NotNull
	private EmailPopulatingBuilder parseEmlIntoEmailBuilder() {
		try {
			final MimeMessage mimeMessage = FinalizedMimeMessage.fromExactMessageBytes(EmailConverter.createDummySession(), emlBytes);
			return EmailConverter.buildEmailFromMimeMessage(
					new EmailStartingBuilderImpl(config).startingBlank(),
					MimeMessageParser.parseMimeMessage(mimeMessage, true));
		} catch (final MessagingException | RuntimeException invalidEml) {
			throw new EmailConverterException("Unable to parse exact EML", invalidEml);
		}
	}
}
