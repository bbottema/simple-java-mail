package org.simplejavamail.api.email;

import org.assertj.core.api.AbstractAssert;
import org.assertj.core.api.Assertions;
import org.simplejavamail.api.email.config.DkimConfig;
import org.simplejavamail.api.email.config.SmimeEncryptionConfig;
import org.simplejavamail.api.email.config.SmimeSigningConfig;

import java.util.Collection;
import java.util.Map;

public class EmailAssert extends AbstractAssert<EmailAssert, Email> {

	public EmailAssert(final Email actual) {
		super(actual, EmailAssert.class);
	}

	public static EmailAssert assertThat(final Email actual) {
		return new EmailAssert(actual);
	}

	public EmailAssert hasFromRecipient(final Recipient fromRecipient) {
		isNotNull();
		Assertions.assertThat(actual.getFromRecipient()).as("fromRecipient").isEqualTo(fromRecipient);
		return this;
	}

	public EmailAssert hasReplyToRecipients(final Recipient... replyToRecipients) {
		isNotNull();
		Assertions.assertThat(actual.getReplyToRecipients()).as("replyToRecipients").contains(replyToRecipients);
		return this;
	}

	public EmailAssert hasNoReplyToRecipients() {
		isNotNull();
		Assertions.assertThat(actual.getReplyToRecipients()).as("replyToRecipients").isEmpty();
		return this;
	}

	public EmailAssert hasBounceToRecipient(final Recipient bounceToRecipient) {
		isNotNull();
		Assertions.assertThat(actual.getBounceToRecipient()).as("bounceToRecipient").isEqualTo(bounceToRecipient);
		return this;
	}

	public EmailAssert hasSubject(final String subject) {
		isNotNull();
		Assertions.assertThat(actual.getSubject()).as("subject").isEqualTo(subject);
		return this;
	}

	public EmailAssert hasUseDispositionNotificationTo(final Boolean useDispositionNotificationTo) {
		isNotNull();
		Assertions.assertThat(actual.getUseDispositionNotificationTo()).as("useDispositionNotificationTo").isEqualTo(useDispositionNotificationTo);
		return this;
	}

	public EmailAssert hasDispositionNotificationTo(final Recipient dispositionNotificationTo) {
		isNotNull();
		Assertions.assertThat(actual.getDispositionNotificationTo()).as("dispositionNotificationTo").isEqualTo(dispositionNotificationTo);
		return this;
	}

	public EmailAssert hasUseReturnReceiptTo(final Boolean useReturnReceiptTo) {
		isNotNull();
		Assertions.assertThat(actual.getUseReturnReceiptTo()).as("useReturnReceiptTo").isEqualTo(useReturnReceiptTo);
		return this;
	}

	public EmailAssert hasReturnReceiptTo(final Recipient returnReceiptTo) {
		isNotNull();
		Assertions.assertThat(actual.getReturnReceiptTo()).as("returnReceiptTo").isEqualTo(returnReceiptTo);
		return this;
	}

	public EmailAssert hasPlainText(final String plainText) {
		isNotNull();
		Assertions.assertThat(actual.getPlainText()).as("plainText").isEqualTo(plainText);
		return this;
	}

	public EmailAssert hasHTMLText(final String htmlText) {
		isNotNull();
		Assertions.assertThat(actual.getHTMLText()).as("htmlText").isEqualTo(htmlText);
		return this;
	}

	public EmailAssert hasCalendarMethod(final CalendarMethod calendarMethod) {
		isNotNull();
		Assertions.assertThat(actual.getCalendarMethod()).as("calendarMethod").isEqualTo(calendarMethod);
		return this;
	}

	public EmailAssert hasCalendarText(final String calendarText) {
		isNotNull();
		Assertions.assertThat(actual.getCalendarText()).as("calendarText").isEqualTo(calendarText);
		return this;
	}

	public EmailAssert hasNoAttachments() {
		isNotNull();
		Assertions.assertThat(actual.getAttachments()).as("attachments").isEmpty();
		return this;
	}

	public EmailAssert hasNoDecryptedAttachments() {
		isNotNull();
		Assertions.assertThat(actual.getDecryptedAttachments()).as("decryptedAttachments").isEmpty();
		return this;
	}

	public EmailAssert hasOnlyEmbeddedImages(final Collection<? extends AttachmentResource> embeddedImages) {
		isNotNull();
		Assertions.assertThat(actual.getEmbeddedImages()).as("embeddedImages").containsOnlyElementsOf(embeddedImages);
		return this;
	}

	public EmailAssert hasRecipients(final Recipient... recipients) {
		isNotNull();
		Assertions.assertThat(actual.getRecipients()).as("recipients").contains(recipients);
		return this;
	}

	public EmailAssert hasOnlyRecipients(final Recipient... recipients) {
		isNotNull();
		Assertions.assertThat(actual.getRecipients()).as("recipients").containsOnly(recipients);
		return this;
	}

	public EmailAssert hasOnlyRecipients(final Collection<? extends Recipient> recipients) {
		isNotNull();
		Assertions.assertThat(actual.getRecipients()).as("recipients").containsOnlyElementsOf(recipients);
		return this;
	}

	public EmailAssert hasNoRecipients() {
		isNotNull();
		Assertions.assertThat(actual.getRecipients()).as("recipients").isEmpty();
		return this;
	}

	public EmailAssert hasHeaders(final Map<String, Collection<String>> headers) {
		isNotNull();
		Assertions.assertThat(actual.getHeaders()).as("headers").isEqualTo(headers);
		return this;
	}

	public EmailAssert hasDkimConfig(final DkimConfig dkimConfig) {
		isNotNull();
		Assertions.assertThat(actual.getDkimConfig()).as("dkimConfig").isEqualTo(dkimConfig);
		return this;
	}

	public EmailAssert hasSmimeEncryptionConfig(final SmimeEncryptionConfig smimeEncryptionConfig) {
		isNotNull();
		Assertions.assertThat(actual.getSmimeEncryptionConfig()).as("smimeEncryptionConfig").isEqualTo(smimeEncryptionConfig);
		return this;
	}

	public EmailAssert hasSmimeSigningConfig(final SmimeSigningConfig smimeSigningConfig) {
		isNotNull();
		Assertions.assertThat(actual.getSmimeSigningConfig()).as("smimeSigningConfig").isEqualTo(smimeSigningConfig);
		return this;
	}

	public EmailAssert hasOriginalSmimeDetails(final OriginalSmimeDetails originalSmimeDetails) {
		isNotNull();
		Assertions.assertThat(actual.getOriginalSmimeDetails()).as("originalSmimeDetails").isEqualTo(originalSmimeDetails);
		return this;
	}
}
