package org.simplejavamail.email.internal;

import org.simplejavamail.api.email.config.EmailTemplateOptions;
import org.simplejavamail.api.email.config.SmtpDeliveryOptions;
import org.junit.jupiter.api.Test;
import org.simplejavamail.api.SimpleJavaMail;
import org.simplejavamail.api.email.Email;
import org.simplejavamail.api.email.EmailPopulatingBuilder;
import org.simplejavamail.api.email.ExactEmailBuilder;
import org.simplejavamail.api.email.Recipient;
import org.simplejavamail.api.email.config.DeliveryStatusNotification;
import org.simplejavamail.api.mailer.Mailer;
import org.simplejavamail.config.ConfigLoader;
import org.simplejavamail.converter.EmailConverter;
import testutil.ConfigLoaderTestHelper;

import java.util.List;
import java.util.Map;

import static java.nio.charset.StandardCharsets.US_ASCII;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.simplejavamail.api.email.config.DeliveryStatusNotification.NotifyOption.FAILURE;
import static org.simplejavamail.api.email.config.DeliveryStatusNotification.ReturnOption.HEADERS_ONLY;
import static org.simplejavamail.api.email.config.DeliveryStatusNotification.ReturnOption.FULL_MESSAGE;
import static org.simplejavamail.api.email.EmailProperty.DELIVERY_STATUS_NOTIFICATION;

class EnvelopeChoiceClearingTest {

	private static final byte[] SOURCE = ("From: visible@example.org\r\n"
			+ "Return-Path: <historical@example.org>\r\nTo: visible-recipient@example.org\r\n"
			+ "Subject: Preserved\r\n\r\nbody\r\n").getBytes(US_ASCII);

	private final SimpleJavaMail mail = SimpleJavaMail.withConfig(ConfigLoaderTestHelper.emptyConfig());

	@Test
	void exactEnvelopeRecipientsCanBeReplacedWithoutChangingAnEarlierEmailOrItsSource() {
		final ExactEmailBuilder builder = exactBuilder().withEnvelopeRecipients("first@example.org", "first@example.org");
		final List<Recipient> initial = builder.getEnvelopeRecipients();
		final Email first = builder.buildEmail();
		assertThat(builder.clearEnvelopeRecipients()).isSameAs(builder);
		assertThat(builder.clearEnvelopeRecipients().getEnvelopeRecipients()).isEmpty();
		assertThatThrownBy(builder::buildEmail).isInstanceOf(IllegalArgumentException.class).hasMessageContaining("envelopeRecipients");
		final Email second = builder.withEnvelopeRecipients("second@example.org").buildEmail();
		assertThat(initial).extracting(Recipient::getAddress).containsExactly("first@example.org", "first@example.org");
		initial.clear();
		assertThat(builder.getEnvelopeRecipients()).extracting(Recipient::getAddress).containsExactly("second@example.org");
		assertThat(first.getOverrideReceivers()).extracting(Recipient::getAddress).containsExactly("first@example.org", "first@example.org");
		assertThat(second.getOverrideReceivers()).extracting(Recipient::getAddress).containsExactly("second@example.org");
		assertExactBytes(first);
		assertExactBytes(second);
	}

	@Test
	void clearingTheExplicitExactSenderRestoresProviderFallbackNotTheHistoricalReturnPath() {
		final ExactEmailBuilder builder = exactBuilder().withEnvelopeRecipients("recipient@example.org")
				.withEnvelopeSender("Bounce <bounce@example.org>");
		final Email first = builder.buildEmail();
		assertThat(builder.getEnvelopeSender().getAddress()).isEqualTo("bounce@example.org");
		assertThat(builder.clearEnvelopeSender()).isSameAs(builder);
		assertThat(builder.clearEnvelopeSender().getEnvelopeSender()).isNull();
		final Email second = builder.buildEmail();
		assertThat(first.getBounceToRecipient().getAddress()).isEqualTo("bounce@example.org");
		assertThat(second.getBounceToRecipient()).isNull();
		assertExactBytes(first);
		assertExactBytes(second);
	}

	@Test
	void wholeExactDsnClearRemovesTheIdentifierButRetainsRecipientPreferences() {
		final Recipient recipient = mail.recipientBuilder().withDeliveryStatusNotificationNotifyOptions(FAILURE)
				.to("Recipient", "recipient@example.org");
		final ExactEmailBuilder builder = exactBuilder().withEnvelopeRecipients(recipient)
				.withSmtpDeliveryOptions(SmtpDeliveryOptions.builder().withDeliveryStatusNotification(completeDsn()).build());
		final Email first = builder.buildEmail();
		assertThat(builder.withSmtpDeliveryOptions(builder.getSmtpDeliveryOptions().toBuilder().clearDeliveryStatusNotification().build())).isSameAs(builder);
		assertThat(builder.withSmtpDeliveryOptions(builder.getSmtpDeliveryOptions().toBuilder().clearDeliveryStatusNotification().build()).getSmtpDeliveryOptions().getDeliveryStatusNotification()).isNull();
		final Email second = builder.buildEmail();
		assertThat(first.getDeliveryStatusNotification()).isEqualTo(completeDsn());
		assertThat(second.getDeliveryStatusNotification()).isNull();
		assertThat(second.getOverrideReceivers().get(0).getDeliveryStatusNotificationNotifyOptions()).containsExactly(FAILURE);
		assertExactBytes(second);
	}

	@Test
	void exactComponentClearsRetainUnrelatedDsnOptionsAndFinallyRestoreAbsence() {
		final ExactEmailBuilder builder = exactBuilder().withEnvelopeRecipients("recipient@example.org")
				.withSmtpDeliveryOptions(SmtpDeliveryOptions.builder().withDeliveryStatusNotification(completeDsn()).build());
		builder.withSmtpDeliveryOptions(builder.getSmtpDeliveryOptions().toBuilder().clearDeliveryStatusNotificationReturnOption().build());
		assertThat(builder.getSmtpDeliveryOptions().getDeliveryStatusNotification().getReturnOption()).isNull();
		assertThat(builder.getSmtpDeliveryOptions().getDeliveryStatusNotification().getNotifyOptions()).containsExactly(FAILURE);
		assertThat(builder.getSmtpDeliveryOptions().getDeliveryStatusNotification().getEnvelopeId()).isEqualTo("fixed-id");
		builder.withSmtpDeliveryOptions(builder.getSmtpDeliveryOptions().toBuilder().clearDeliveryStatusNotificationNotifyOptions().build());
		assertThat(builder.getSmtpDeliveryOptions().getDeliveryStatusNotification().getNotifyOptions()).isEmpty();
		assertThat(builder.getSmtpDeliveryOptions().getDeliveryStatusNotification().getEnvelopeId()).isEqualTo("fixed-id");
		builder.withSmtpDeliveryOptions(builder.getSmtpDeliveryOptions().toBuilder().clearEnvelopeId().clearEnvelopeId().build());
		assertThat(builder.getSmtpDeliveryOptions().getDeliveryStatusNotification()).isNull();
		assertThat(builder.buildEmail().getDeliveryStatusNotification()).isNull();
		assertExactBytes(builder.buildEmail());
	}

	@Test
	void eachExactDsnComponentCanBeClearedWhenItWasTheOnlyChoice() {
		final ExactEmailBuilder builder = exactBuilder().withEnvelopeRecipients("recipient@example.org");
		assertThat(builder.withSmtpDeliveryOptions(builder.getSmtpDeliveryOptions().toBuilder().withDeliveryStatusNotificationReturnOption(HEADERS_ONLY).clearDeliveryStatusNotificationReturnOption().build()).buildEmail().getDeliveryStatusNotification()).isNull();
		assertThat(builder.withSmtpDeliveryOptions(builder.getSmtpDeliveryOptions().toBuilder().withDeliveryStatusNotificationNotifyOptions(FAILURE).clearDeliveryStatusNotificationNotifyOptions().build()).buildEmail().getDeliveryStatusNotification()).isNull();
		assertThat(builder.withSmtpDeliveryOptions(builder.getSmtpDeliveryOptions().toBuilder().fixingEnvelopeId("fixed-id").clearEnvelopeId().build()).buildEmail().getDeliveryStatusNotification()).isNull();
	}

	@Test
	void composedComponentClearsRetainOtherOptionsAndPreviouslyBuiltEmails() {
		final EmailPopulatingBuilder builder = mail.emailBuilder().startingBlank().withSmtpDeliveryOptions(SmtpDeliveryOptions.builder().withDeliveryStatusNotification(completeDsn()).build());
		final Email first = builder.buildEmail();
		builder.withSmtpDeliveryOptions(builder.getSmtpDeliveryOptions().toBuilder().clearDeliveryStatusNotificationReturnOption().build());
		assertThat(builder.getSmtpDeliveryOptions().getDeliveryStatusNotification().getReturnOption()).isNull();
		assertThat(builder.getSmtpDeliveryOptions().getDeliveryStatusNotification().getNotifyOptions()).containsExactly(FAILURE);
		assertThat(builder.getSmtpDeliveryOptions().getDeliveryStatusNotification().getEnvelopeId()).isEqualTo("fixed-id");
		builder.withSmtpDeliveryOptions(builder.getSmtpDeliveryOptions().toBuilder().clearDeliveryStatusNotificationNotifyOptions().build());
		assertThat(builder.getSmtpDeliveryOptions().getDeliveryStatusNotification().getNotifyOptions()).isEmpty();
		assertThat(builder.getSmtpDeliveryOptions().getDeliveryStatusNotification().getEnvelopeId()).isEqualTo("fixed-id");
		builder.withSmtpDeliveryOptions(builder.getSmtpDeliveryOptions().toBuilder().clearEnvelopeId().clearEnvelopeId().build());
		assertThat(builder.getSmtpDeliveryOptions().getDeliveryStatusNotification()).isNull();
		assertThat(first.getDeliveryStatusNotification()).isEqualTo(completeDsn());
	}

	@Test
	void eachComposedDsnComponentCanBeClearedWhenItWasTheOnlyChoice() {
		final EmailPopulatingBuilder builder = mail.emailBuilder().startingBlank();
		assertThat(builder.withSmtpDeliveryOptions(builder.getSmtpDeliveryOptions().toBuilder().withDeliveryStatusNotificationReturnOption(HEADERS_ONLY).clearDeliveryStatusNotificationReturnOption().build()).buildEmail().getDeliveryStatusNotification()).isNull();
		assertThat(builder.withSmtpDeliveryOptions(builder.getSmtpDeliveryOptions().toBuilder().withDeliveryStatusNotificationNotifyOptions(FAILURE).clearDeliveryStatusNotificationNotifyOptions().build()).buildEmail().getDeliveryStatusNotification()).isNull();
		assertThat(builder.withSmtpDeliveryOptions(builder.getSmtpDeliveryOptions().toBuilder().fixingEnvelopeId("fixed-id").clearEnvelopeId().build()).buildEmail().getDeliveryStatusNotification()).isNull();
	}

	@Test
	void exactTlsGetterReflectsOnlyTheBuildersOwnRequirement() {
		final ExactEmailBuilder builder = exactBuilder().withEnvelopeRecipients("recipient@example.org");
		assertThat(builder.getSmtpDeliveryOptions().isTlsRequiredForOnwardDelivery()).isFalse();
		assertThat(builder.withSmtpDeliveryOptions(builder.getSmtpDeliveryOptions().toBuilder().withTlsRequiredForOnwardDelivery().build()).getSmtpDeliveryOptions().isTlsRequiredForOnwardDelivery()).isTrue();
		assertThat(builder.withSmtpDeliveryOptions(builder.getSmtpDeliveryOptions().toBuilder().clearTlsRequiredForOnwardDelivery().build()).getSmtpDeliveryOptions().isTlsRequiredForOnwardDelivery()).isFalse();
	}

	@Test
	void clearingComposedDsnRestoresOrdinaryDefaultsUnlessTheyAreSuppressed() {
		final SimpleJavaMail configured = SimpleJavaMail.withConfig(ConfigLoader.builder().withMap(Map.of(
				"simplejavamail.defaults.delivery.status.notification.return.option", "HDRS")).load());
		final EmailPopulatingBuilder builder = configured.emailBuilder().startingBlank()
				.withSmtpDeliveryOptions(SmtpDeliveryOptions.builder().withDeliveryStatusNotificationReturnOption(FULL_MESSAGE).clearDeliveryStatusNotificationReturnOption().build());
		assertThat(builder.buildEmailCompletedWithDefaultsAndOverrides().getDeliveryStatusNotification().getReturnOption()).isEqualTo(HEADERS_ONLY);
		assertThat(builder.withTemplateOptions(builder.getTemplateOptions().toBuilder().dontApplyDefaultValueFor(DELIVERY_STATUS_NOTIFICATION).build())
				.buildEmailCompletedWithDefaultsAndOverrides().getDeliveryStatusNotification()).isNull();
	}

	@Test
	void clearingAndSuppressingDsnDoesNotBypassConfiguredLocksOrRewriteExactBytes() throws Exception {
		final SimpleJavaMail configured = SimpleJavaMail.withConfig(ConfigLoader.builder().withMap(Map.of(
				"simplejavamail.locked.defaults.delivery.status.notification.return.option", "HDRS")).load());
		final Email composed = configured.emailBuilder().startingBlank().withSmtpDeliveryOptions(SmtpDeliveryOptions.builder().clearDeliveryStatusNotificationReturnOption().clearDeliveryStatusNotification().build()).withTemplateOptions(EmailTemplateOptions.builder().ignoringDefaults().dontApplyDefaultValueFor(DELIVERY_STATUS_NOTIFICATION).build())
				.buildEmailCompletedWithDefaultsAndOverrides();
		assertThat(composed.getDeliveryStatusNotification().getReturnOption()).isEqualTo(HEADERS_ONLY);
		final Email exact = exactBuilder().withEnvelopeRecipients("recipient@example.org")
				.withSmtpDeliveryOptions(SmtpDeliveryOptions.builder().withDeliveryStatusNotificationReturnOption(FULL_MESSAGE).clearDeliveryStatusNotification().build()).buildEmail();
		try (Mailer mailer = configured.mailerBuilder().withTransportModeLoggingOnly(true).buildMailer()) {
			final Email effective = mailer.rehearse(exact).getEffectiveEmail();
			assertThat(effective.getDeliveryStatusNotification().getReturnOption()).isEqualTo(HEADERS_ONLY);
			assertExactBytes(effective);
		}
	}

	private ExactEmailBuilder exactBuilder() {
		return mail.emailBuilder().startingFromExactEml(SOURCE);
	}

	private static DeliveryStatusNotification completeDsn() {
		return DeliveryStatusNotification.builder().returnOption(HEADERS_ONLY).notifyOptions(FAILURE).envelopeId("fixed-id").build();
	}

	private static void assertExactBytes(final Email email) {
		assertThat(EmailConverter.emailToEMLByteArray(email)).containsExactly(SOURCE);
	}
}
