package org.simplejavamail.email.internal;

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
				.withDeliveryStatusNotification(completeDsn());
		final Email first = builder.buildEmail();
		assertThat(builder.clearDeliveryStatusNotification()).isSameAs(builder);
		assertThat(builder.clearDeliveryStatusNotification().getDeliveryStatusNotification()).isNull();
		final Email second = builder.buildEmail();
		assertThat(first.getDeliveryStatusNotification()).isEqualTo(completeDsn());
		assertThat(second.getDeliveryStatusNotification()).isNull();
		assertThat(second.getOverrideReceivers().get(0).getDeliveryStatusNotificationNotifyOptions()).containsExactly(FAILURE);
		assertExactBytes(second);
	}

	@Test
	void exactComponentClearsRetainUnrelatedDsnOptionsAndFinallyRestoreAbsence() {
		final ExactEmailBuilder builder = exactBuilder().withEnvelopeRecipients("recipient@example.org")
				.withDeliveryStatusNotification(completeDsn());
		builder.clearDeliveryStatusNotificationReturnOption();
		assertThat(builder.getDeliveryStatusNotification().getReturnOption()).isNull();
		assertThat(builder.getDeliveryStatusNotification().getNotifyOptions()).containsExactly(FAILURE);
		assertThat(builder.getDeliveryStatusNotification().getEnvelopeId()).isEqualTo("fixed-id");
		builder.clearDeliveryStatusNotificationNotifyOptions();
		assertThat(builder.getDeliveryStatusNotification().getNotifyOptions()).isEmpty();
		assertThat(builder.getDeliveryStatusNotification().getEnvelopeId()).isEqualTo("fixed-id");
		builder.clearEnvelopeId().clearEnvelopeId();
		assertThat(builder.getDeliveryStatusNotification()).isNull();
		assertThat(builder.buildEmail().getDeliveryStatusNotification()).isNull();
		assertExactBytes(builder.buildEmail());
	}

	@Test
	void eachExactDsnComponentCanBeClearedWhenItWasTheOnlyChoice() {
		final ExactEmailBuilder builder = exactBuilder().withEnvelopeRecipients("recipient@example.org");
		assertThat(builder.withDeliveryStatusNotificationReturnOption(HEADERS_ONLY)
				.clearDeliveryStatusNotificationReturnOption().buildEmail().getDeliveryStatusNotification()).isNull();
		assertThat(builder.withDeliveryStatusNotificationNotifyOptions(FAILURE)
				.clearDeliveryStatusNotificationNotifyOptions().buildEmail().getDeliveryStatusNotification()).isNull();
		assertThat(builder.fixingEnvelopeId("fixed-id").clearEnvelopeId().buildEmail().getDeliveryStatusNotification()).isNull();
	}

	@Test
	void composedComponentClearsRetainOtherOptionsAndPreviouslyBuiltEmails() {
		final EmailPopulatingBuilder builder = mail.emailBuilder().startingBlank().withDeliveryStatusNotification(completeDsn());
		final Email first = builder.buildEmail();
		builder.clearDeliveryStatusNotificationReturnOption();
		assertThat(builder.getDeliveryStatusNotification().getReturnOption()).isNull();
		assertThat(builder.getDeliveryStatusNotification().getNotifyOptions()).containsExactly(FAILURE);
		assertThat(builder.getDeliveryStatusNotification().getEnvelopeId()).isEqualTo("fixed-id");
		builder.clearDeliveryStatusNotificationNotifyOptions();
		assertThat(builder.getDeliveryStatusNotification().getNotifyOptions()).isEmpty();
		assertThat(builder.getDeliveryStatusNotification().getEnvelopeId()).isEqualTo("fixed-id");
		builder.clearEnvelopeId().clearEnvelopeId();
		assertThat(builder.getDeliveryStatusNotification()).isNull();
		assertThat(first.getDeliveryStatusNotification()).isEqualTo(completeDsn());
	}

	@Test
	void eachComposedDsnComponentCanBeClearedWhenItWasTheOnlyChoice() {
		final EmailPopulatingBuilder builder = mail.emailBuilder().startingBlank();
		assertThat(builder.withDeliveryStatusNotificationReturnOption(HEADERS_ONLY)
				.clearDeliveryStatusNotificationReturnOption().buildEmail().getDeliveryStatusNotification()).isNull();
		assertThat(builder.withDeliveryStatusNotificationNotifyOptions(FAILURE)
				.clearDeliveryStatusNotificationNotifyOptions().buildEmail().getDeliveryStatusNotification()).isNull();
		assertThat(builder.fixingEnvelopeId("fixed-id").clearEnvelopeId().buildEmail().getDeliveryStatusNotification()).isNull();
	}

	@Test
	void exactTlsGetterReflectsOnlyTheBuildersOwnRequirement() {
		final ExactEmailBuilder builder = exactBuilder().withEnvelopeRecipients("recipient@example.org");
		assertThat(builder.isTlsRequiredForOnwardDelivery()).isFalse();
		assertThat(builder.withTlsRequiredForOnwardDelivery().isTlsRequiredForOnwardDelivery()).isTrue();
		assertThat(builder.clearTlsRequiredForOnwardDelivery().isTlsRequiredForOnwardDelivery()).isFalse();
	}

	@Test
	void clearingComposedDsnRestoresOrdinaryDefaultsUnlessTheyAreSuppressed() {
		final SimpleJavaMail configured = SimpleJavaMail.withConfig(ConfigLoader.builder().withMap(Map.of(
				"simplejavamail.defaults.delivery.status.notification.return.option", "HDRS")).load());
		final EmailPopulatingBuilder builder = configured.emailBuilder().startingBlank()
				.withDeliveryStatusNotificationReturnOption(FULL_MESSAGE).clearDeliveryStatusNotificationReturnOption();
		assertThat(builder.buildEmailCompletedWithDefaultsAndOverrides().getDeliveryStatusNotification().getReturnOption()).isEqualTo(HEADERS_ONLY);
		assertThat(builder.dontApplyDefaultValueFor(DELIVERY_STATUS_NOTIFICATION)
				.buildEmailCompletedWithDefaultsAndOverrides().getDeliveryStatusNotification()).isNull();
	}

	@Test
	void clearingAndSuppressingDsnDoesNotBypassConfiguredLocksOrRewriteExactBytes() throws Exception {
		final SimpleJavaMail configured = SimpleJavaMail.withConfig(ConfigLoader.builder().withMap(Map.of(
				"simplejavamail.locked.defaults.delivery.status.notification.return.option", "HDRS")).load());
		final Email composed = configured.emailBuilder().startingBlank().clearDeliveryStatusNotificationReturnOption()
				.clearDeliveryStatusNotification().ignoringDefaults().dontApplyDefaultValueFor(DELIVERY_STATUS_NOTIFICATION)
				.buildEmailCompletedWithDefaultsAndOverrides();
		assertThat(composed.getDeliveryStatusNotification().getReturnOption()).isEqualTo(HEADERS_ONLY);
		final Email exact = exactBuilder().withEnvelopeRecipients("recipient@example.org")
				.withDeliveryStatusNotificationReturnOption(FULL_MESSAGE).clearDeliveryStatusNotification().buildEmail();
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
