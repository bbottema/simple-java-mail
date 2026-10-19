package org.simplejavamail.mailer.internal;

import jakarta.mail.Message.RecipientType;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.simplejavamail.api.SimpleJavaMail;
import org.simplejavamail.api.email.Email;
import org.simplejavamail.api.email.EmailPopulatingBuilder;
import org.simplejavamail.api.email.EmailProperty;
import org.simplejavamail.api.email.Recipient;
import org.simplejavamail.api.mailer.Mailer;
import org.simplejavamail.config.ConfigLoader;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.ObjectInputStream;
import java.io.ObjectOutputStream;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.simplejavamail.api.email.EmailProperty.ALL_RECIPIENTS;

class AllRecipientsSuppressionTest {

	private final SimpleJavaMail mail = SimpleJavaMail.withConfig(ConfigLoader.builder().load());

	@Test
	void suppressesAllDefaultRecipientTypesWithoutSuppressingOverridesOrTheDeliveryOverride() {
		final Email provided = message("provided").dontApplyDefaultValueFor(ALL_RECIPIENTS).buildEmail();

		final Email resolved = govern(provided);

		assertThat(resolved.getRecipients()).extracting(Recipient::getAddress).containsExactly(
				"override-to@example.org", "provided-to@example.org",
				"override-cc@example.org", "provided-cc@example.org",
				"override-bcc@example.org", "provided-bcc@example.org");
		assertThat(resolved.getOverrideReceivers()).extracting(Recipient::getAddress).containsExactly(
				"override-envelope@example.org", "provided-envelope@example.org", "default-envelope@example.org");
	}

	@Test
	void suppressesAllOverrideRecipientTypesWithoutSuppressingDefaultsOrTheDeliveryOverride() {
		final Email provided = message("provided").dontApplyOverrideValueFor(ALL_RECIPIENTS).buildEmail();

		final Email resolved = govern(provided);

		assertThat(resolved.getRecipients()).extracting(Recipient::getAddress).containsExactly(
				"provided-to@example.org", "default-to@example.org",
				"provided-cc@example.org", "default-cc@example.org",
				"provided-bcc@example.org", "default-bcc@example.org");
		assertThat(resolved.getOverrideReceivers()).extracting(Recipient::getAddress).containsExactly(
				"override-envelope@example.org", "provided-envelope@example.org", "default-envelope@example.org");
	}

	@ParameterizedTest
	@EnumSource(value = EmailProperty.class, names = {"TO_RECIPIENTS", "CC_RECIPIENTS", "BCC_RECIPIENTS"})
	void individualRecipientSuppressionLeavesOtherRecipientTypesAlone(final EmailProperty property) {
		final Email provided = message("provided").dontApplyDefaultValueFor(property).dontApplyOverrideValueFor(property).buildEmail();

		final Email resolved = govern(provided);

		for (final RecipientType recipientType : new RecipientType[]{RecipientType.TO, RecipientType.CC, RecipientType.BCC}) {
			final String suffix = recipientType.toString().toLowerCase(java.util.Locale.ROOT);
			if (property.name().startsWith(recipientType.toString().toUpperCase(java.util.Locale.ROOT) + "_")) {
				assertThat(resolved.getRecipients()).filteredOn(recipient -> recipient.getType() == recipientType)
						.extracting(Recipient::getAddress).containsExactly("provided-" + suffix + "@example.org");
			} else {
				assertThat(resolved.getRecipients()).filteredOn(recipient -> recipient.getType() == recipientType)
						.extracting(Recipient::getAddress).containsExactly(
								"override-" + suffix + "@example.org", "provided-" + suffix + "@example.org", "default-" + suffix + "@example.org");
			}
		}
	}

	@Test
	void globalSuppressionStillRemovesBothVisibleAndDeliveryOverrideTemplateRecipients() {
		final Email provided = message("provided").ignoringDefaults().ignoringOverrides().buildEmail();

		final Email resolved = govern(provided);

		assertThat(resolved.getRecipients()).containsExactlyElementsOf(provided.getRecipients());
		assertThat(resolved.getOverrideReceivers()).containsExactlyElementsOf(provided.getOverrideReceivers());
	}

	@Test
	void copyingAndSerializationRetainAllRecipientSuppression() throws Exception {
		final Email original = message("provided").dontApplyDefaultValueFor(ALL_RECIPIENTS).dontApplyOverrideValueFor(ALL_RECIPIENTS).buildEmail();
		final Email copied = mail.emailBuilder().copying(original).buildEmail();
		final Email restored = serializeAndRestore(original);

		for (final Email candidate : new Email[]{original, copied, restored}) {
			assertThat(candidate.getPropertiesNotToApplyDefaultValueFor()).containsExactly(ALL_RECIPIENTS);
			assertThat(candidate.getPropertiesNotToApplyOverrideValueFor()).containsExactly(ALL_RECIPIENTS);
			assertThat(govern(candidate).getRecipients()).containsExactlyElementsOf(original.getRecipients());
		}
	}

	@Test
	void mandatoryArchiveRecipientSurvivesAllRecipientAndGlobalSuppression() throws Exception {
		final SimpleJavaMail locked = SimpleJavaMail.withConfig(ConfigLoader.builder().withMap(Map.of(
				"simplejavamail.locked.defaults.bcc.address", "archive@example.org")).load());
		final Email provided = message("provided").dontApplyDefaultValueFor(ALL_RECIPIENTS).dontApplyOverrideValueFor(ALL_RECIPIENTS)
				.ignoringDefaults().ignoringOverrides().buildEmail();
		try (Mailer mailer = locked.mailerBuilder().withTransportModeLoggingOnly(true).buildMailer()) {
			final Email resolved = mailer.getEmailGovernance().produceEmailApplyingDefaultsAndOverrides(provided);

			assertThat(resolved.getBccRecipients()).extracting(Recipient::getAddress)
					.containsExactly("provided-bcc@example.org", "archive@example.org");
			assertThat(mailer.rehearse(provided).getEnvelopeRecipients()).containsExactly("provided-envelope@example.org", "archive@example.org");
		}
	}

	private Email govern(final Email provided) {
		return new EmailGovernanceImpl(null, message("default").buildEmail(), message("override").buildEmail(), null)
				.produceEmailApplyingDefaultsAndOverrides(provided);
	}

	private EmailPopulatingBuilder message(final String prefix) {
		return mail.emailBuilder().startingBlank().from("sender@example.org").withSubject("Recipient suppression").withPlainText("body")
				.withRecipients(new Recipient(null, prefix + "-to@example.org", RecipientType.TO, null),
						new Recipient(null, prefix + "-cc@example.org", RecipientType.CC, null),
						new Recipient(null, prefix + "-bcc@example.org", RecipientType.BCC, null))
				.withOverrideReceivers(new Recipient(null, prefix + "-envelope@example.org", null, null));
	}

	private static Email serializeAndRestore(final Email original) throws Exception {
		final ByteArrayOutputStream bytes = new ByteArrayOutputStream();
		try (ObjectOutputStream output = new ObjectOutputStream(bytes)) {
			output.writeObject(original);
		}
		try (ObjectInputStream input = new ObjectInputStream(new ByteArrayInputStream(bytes.toByteArray()))) {
			return (Email) input.readObject();
		}
	}
}
