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
import org.simplejavamail.config.SimpleJavaMailConfig;
import org.simplejavamail.converter.internal.mimemessage.MimeMessageParser;
import org.simplejavamail.email.internal.EmailStartingBuilderImpl;
import org.simplejavamail.email.internal.ExactEmlValidator;
import org.simplejavamail.email.internal.InternalEmail;
import org.simplejavamail.internal.util.FinalizedMimeMessage;

import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

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
	private final Set<DeliveryStatusNotification.NotifyOption> notifyOptions = new LinkedHashSet<>();
	@Nullable
	private Recipient envelopeSender;
	@Nullable
	private DeliveryStatusNotification.ReturnOption returnOption;
	private boolean deliveryStatusNotificationConfigured;

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
		final DeliveryStatusNotification notification = requireNonNull(deliveryStatusNotification, "deliveryStatusNotification");
		returnOption = notification.getReturnOption();
		notifyOptions.clear();
		notifyOptions.addAll(notification.getNotifyOptions());
		deliveryStatusNotificationConfigured = true;
		return this;
	}

	/**
	 * @see ExactEmailBuilder#withDeliveryStatusNotificationNotifyOptions(String)
	 */
	@Override
	public ExactEmailBuilder withDeliveryStatusNotificationNotifyOptions(@NotNull final String notifyOptions) {
		this.notifyOptions.clear();
		this.notifyOptions.addAll(DeliveryStatusNotification.parseNotifyOptions(notifyOptions));
		deliveryStatusNotificationConfigured = true;
		return this;
	}

	/**
	 * @see ExactEmailBuilder#withDeliveryStatusNotificationReturnOption(String)
	 */
	@Override
	public ExactEmailBuilder withDeliveryStatusNotificationReturnOption(@NotNull final String returnOption) {
		this.returnOption = DeliveryStatusNotification.parseReturnOption(returnOption);
		deliveryStatusNotificationConfigured = true;
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
		if (deliveryStatusNotificationConfigured) {
			parsedEmailBuilder.withDeliveryStatusNotification(
					DeliveryStatusNotification.of(returnOption, notifyOptions));
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
