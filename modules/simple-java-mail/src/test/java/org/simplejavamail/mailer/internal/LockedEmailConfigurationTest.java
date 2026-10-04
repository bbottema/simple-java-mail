package org.simplejavamail.mailer.internal;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.simplejavamail.api.SimpleJavaMail;
import org.simplejavamail.api.email.Email;
import org.simplejavamail.api.email.Recipient;
import org.simplejavamail.api.email.config.DeliveryStatusNotification;
import org.simplejavamail.api.email.config.DkimConfig;
import org.simplejavamail.api.mailer.Mailer;
import org.simplejavamail.config.ConfigLoader;
import org.simplejavamail.email.internal.InternalEmail;

import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.Properties;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.simplejavamail.internal.config.EmailProperty.SUBJECT;

class LockedEmailConfigurationTest {

	private final SimpleJavaMail ordinary = SimpleJavaMail.withConfig(ConfigLoader.builder().load());

	@Test
	void singleValueLocksSurviveSuppressionAndTemplateReplacementButRejectExplicitConflicts() throws Exception {
		final SimpleJavaMail factory = locked(Map.of("defaults.subject", "Company mail", "defaults.requiretls", "true"));
		final Email blank = ordinary.emailBuilder().startingBlank().ignoringDefaults().ignoringOverrides().dontApplyDefaultValueFor(SUBJECT).buildEmail();
		try (Mailer mailer = factory.mailerBuilder().withTransportModeLoggingOnly(true).withEmailDefaults(blank).withEmailOverrides(blank).buildMailer()) {
			final Email prepared = mailer.getEmailGovernance().produceEmailApplyingDefaultsAndOverrides(blank);
			assertThat(prepared.getSubject()).isEqualTo("Company mail");
			assertThat(prepared.isTlsRequiredForOnwardDelivery()).isTrue();
			assertThat(mailer.getEmailGovernance().produceEmailApplyingDefaultsAndOverrides(prepared).getSubject()).isEqualTo("Company mail");
			final Email conflict = ordinary.emailBuilder().startingBlank().withSubject("Other subject").buildEmail();
			assertThatThrownBy(() -> mailer.getEmailGovernance().produceEmailApplyingDefaultsAndOverrides(conflict))
					.hasMessageContaining("locked.defaults.subject").hasMessageNotContaining("Other subject")
					.hasMessageContaining("Email, a recipient, or a defaults/overrides template")
					.hasMessageContaining("Remove the conflicting field so the lock can supply it");
		}
	}

	@Test
	void everyMailerInheritsLocksButAnIndependentFactoryDoesNot() throws Exception {
		final SimpleJavaMail factory = locked(Map.of("defaults.subject", "Company mail"));
		final Email blank = ordinary.emailBuilder().startingBlank().buildEmailCompletedWithDefaultsAndOverrides();
		for (int index = 0; index < 2; index++) {
			try (Mailer mailer = factory.mailerBuilder().withTransportModeLoggingOnly(true).buildMailer()) {
				assertThat(mailer.getEmailGovernance().produceEmailApplyingDefaultsAndOverrides(blank).getSubject()).isEqualTo("Company mail");
			}
		}
		assertThat(ordinary.emailBuilder().startingBlank().buildEmailCompletedWithDefaultsAndOverrides().getSubject()).isNull();
	}

	@Test
	void archiveRecipientsAreAddedToTheActualOverrideEnvelopeWithoutGrowingOnRepeatedPreparation() throws Exception {
		final SimpleJavaMail factory = locked(Map.of("defaults.bcc.address", "archive@example.org"));
		final Email input = ordinary.emailBuilder().startingBlank().from("sender@example.org")
				.withRecipients(new Recipient(null, "visible@example.org", jakarta.mail.Message.RecipientType.TO, null))
				.withOverrideReceivers(new Recipient(null, "test@example.org", null, null)).buildEmail();
		try (Mailer mailer = factory.mailerBuilder().withTransportModeLoggingOnly(true).buildMailer()) {
			final Email once = mailer.getEmailGovernance().produceEmailApplyingDefaultsAndOverrides(input);
			final Email twice = mailer.getEmailGovernance().produceEmailApplyingDefaultsAndOverrides(once);
			assertThat(once.getBccRecipients()).extracting("address").containsExactly("archive@example.org");
			assertThat(twice.getOverrideReceivers()).extracting("address").containsExactly("test@example.org", "archive@example.org");
			assertThat(mailer.rehearse(input).getEnvelopeRecipients()).containsExactly("test@example.org", "archive@example.org");
			assertThat(input.getBccRecipients()).isEmpty();
		}
	}

	@Test
	void exactEmlKeepsItsBytesWhenOnlyEnvelopeLocksAreNeeded() throws Exception {
		final byte[] bytes = ("From: sender@example.org\r\nTo: visible@example.org\r\nSubject: Original\r\n\r\nbody\r\n")
				.getBytes(StandardCharsets.US_ASCII);
		final Email exact = ordinary.emailBuilder().startingFromExactEml(bytes).withEnvelopeRecipients("test@example.org").buildEmail();
		try (Mailer mailer = locked(Map.of("defaults.bcc.address", "archive@example.org")).mailerBuilder().withTransportModeLoggingOnly(true).buildMailer()) {
			assertThat(mailer.rehearse(exact).getEmlBytes()).containsExactly(bytes);
			assertThat(mailer.rehearse(exact).getEnvelopeRecipients()).containsExactly("test@example.org", "archive@example.org");
		}
		try (Mailer mailer = locked(Map.of("defaults.subject", "Original")).mailerBuilder().withTransportModeLoggingOnly(true).buildMailer()) {
			assertThat(mailer.rehearse(exact).getEmlBytes()).containsExactly(bytes);
		}
		try (Mailer mailer = locked(Map.of("defaults.subject", "Changed")).mailerBuilder().withTransportModeLoggingOnly(true).buildMailer()) {
			assertThatThrownBy(() -> mailer.rehearse(exact)).hasMessageContaining("exact EML")
					.hasMessageContaining("original bytes, including any signatures").hasMessageContaining("Use a composed Email")
					.hasMessageContaining("remove this lock");
		}
	}

	@Test
	void rawHeadersCannotReplaceLockedBuilderFields() throws Exception {
		try (Mailer mailer = locked(Map.of("defaults.bcc.address", "archive@example.org")).mailerBuilder().withTransportModeLoggingOnly(true).buildMailer()) {
			final Email input = ordinary.emailBuilder().startingBlank().withHeader("bCC", "elsewhere@example.org").buildEmail();
			assertThatThrownBy(() -> mailer.getEmailGovernance().produceEmailApplyingDefaultsAndOverrides(input))
					.hasMessageContaining("withHeader(\"Bcc\", ...)").hasMessageContaining("locked.defaults.bcc.address")
					.hasMessageContaining("could bypass this factory's locked Email setting").hasMessageContaining("remove this lock")
					.hasMessageNotContaining("elsewhere@example.org");
		}
	}

	@ParameterizedTest
	@CsvSource(delimiter = '|', value = {
			"defaults.subject|Subject|Company mail|Company mail|Different subject",
			"defaults.from.address|From|sender@example.org|sender@example.org|other@example.org",
			"defaults.from.name|From|Company|Company <sender@example.org>|Other <sender@example.org>",
			"defaults.replyto.address|Reply-To|reply@example.org|reply@example.org|other@example.org",
			"defaults.replyto.name|Reply-To|Company|Company <reply@example.org>|Other <reply@example.org>"
	})
	void exactEmlWithConflictingDuplicateLockedHeadersIsRejected(final String property, final String header, final String lockedValue,
			final String firstValue, final String secondValue) throws Exception {
		final String remainingHeaders = "To: receiver@example.org\r\n"
				+ (header.equals("From") ? "" : "From: sender@example.org\r\n");
		final byte[] bytes = (remainingHeaders + header + ": " + firstValue + "\r\n" + header.toLowerCase(java.util.Locale.ROOT)
				+ ": " + secondValue + "\r\n\r\nbody\r\n").getBytes(StandardCharsets.US_ASCII);
		final Email exact = ordinary.emailBuilder().startingFromExactEml(bytes).withEnvelopeRecipients("receiver@example.org").buildEmail();
		try (Mailer mailer = locked(Map.of(property, lockedValue)).mailerBuilder().withTransportModeLoggingOnly(true).buildMailer()) {
			assertThatThrownBy(() -> mailer.validate(exact)).hasMessageContaining("locked." + property)
					.hasMessageContaining("more than one " + header + " header").hasMessageContaining("exact EML")
					.hasMessageContaining("cannot choose which value a mail client will use")
					.hasMessageContaining("Supply exact EML with one " + header + " header").hasMessageContaining("remove this lock")
					.hasMessageNotContaining(firstValue).hasMessageNotContaining(secondValue);
			assertThatThrownBy(() -> mailer.rehearse(exact)).hasMessageContaining("locked." + property);
			assertThatThrownBy(() -> mailer.rehearse(exact, false)).hasMessageContaining("locked." + property);
			assertThatThrownBy(() -> mailer.sync().sendMail(exact)).hasMessageContaining("locked." + property);
		}
		try (Mailer mailer = ordinary.mailerBuilder().withTransportModeLoggingOnly(true).buildMailer()) {
			assertThat(mailer.rehearse(exact).getEmlBytes()).containsExactly(bytes);
		}
	}

	@Test
	void evenIdenticalDuplicateSubjectHeadersCannotConfirmALockedExactSubject() throws Exception {
		final byte[] bytes = ("From: sender@example.org\r\nTo: receiver@example.org\r\nSubject: Company mail\r\n"
				+ "Subject: Company mail\r\n\r\nbody\r\n").getBytes(StandardCharsets.US_ASCII);
		final Email exact = ordinary.emailBuilder().startingFromExactEml(bytes).withEnvelopeRecipients("receiver@example.org").buildEmail();
		try (Mailer mailer = locked(Map.of("defaults.subject", "Company mail")).mailerBuilder().withTransportModeLoggingOnly(true).buildMailer()) {
			assertThatThrownBy(() -> mailer.rehearse(exact)).hasMessageContaining("more than one Subject header");
		}
	}

	@Test
	void matchingEncodedHeadersAndAnAdditiveReplyToListKeepExactBytes() throws Exception {
		final byte[] bytes = ("From: sender@example.org\r\nTo: receiver@example.org\r\nSubject: =?UTF-8?Q?Company_mail?=\r\n"
				+ "Reply-To: reply@example.org, other@example.org\r\n\r\nbody\r\n").getBytes(StandardCharsets.US_ASCII);
		final Email exact = ordinary.emailBuilder().startingFromExactEml(bytes).withEnvelopeRecipients("receiver@example.org").buildEmail();
		try (Mailer mailer = locked(Map.of("defaults.subject", "Company mail", "defaults.replyto.address", "reply@example.org"))
				.mailerBuilder().withTransportModeLoggingOnly(true).buildMailer()) {
			assertThat(mailer.rehearse(exact).getEmlBytes()).containsExactly(bytes);
		}
	}

	@Test
	void dkimLocksRestrictOnlyTheSelectedField() throws Exception {
		final DkimConfig dkim = DkimConfig.builder().dkimPrivateKeyData("not-used-for-signing").dkimSigningDomain("example.org")
				.dkimSelector("company").signingAlgorithm("SHA256_WITH_RSA").build();
		try (Mailer mailer = locked(Map.of("dkim.signing.selector", "company")).mailerBuilder().withTransportModeLoggingOnly(true).buildMailer()) {
			final Email input = ordinary.emailBuilder().startingBlank().signWithDomainKey(dkim).buildEmail();
			final Email resolved = mailer.getEmailGovernance().produceEmailApplyingDefaultsAndOverrides(input);
			assertThat(resolved.getDkimConfig()).isEqualTo(dkim);
			final DkimConfig conflict = DkimConfig.builder().dkimPrivateKeyData("key").dkimSigningDomain("example.org").dkimSelector("other").build();
			assertThatThrownBy(() -> mailer.getEmailGovernance().produceEmailApplyingDefaultsAndOverrides(
					ordinary.emailBuilder().startingBlank().signWithDomainKey(conflict).buildEmail())).hasMessageContaining("locked.dkim.signing.selector");
		}
	}

	@Test
	void lockedDsnIsMandatoryAndRecipientPreferencesCannotWeakenIt() throws Exception {
		try (Mailer mailer = locked(Map.of("defaults.delivery.status.notification.notify", "FAILURE"))
				.mailerBuilder().withTransportModeLoggingOnly(true).buildMailer()) {
			final Email resolved = mailer.getEmailGovernance().produceEmailApplyingDefaultsAndOverrides(ordinary.emailBuilder().startingBlank().buildEmail());
			assertThat(InternalEmail.requireInternalEmail(resolved).isDeliveryStatusNotificationRequired()).isTrue();
			assertThat(resolved.getDeliveryStatusNotification().getNotifyOptions()).containsExactly(DeliveryStatusNotification.NotifyOption.FAILURE);
			final Recipient conflicting = new Recipient(null, "recipient@example.org", jakarta.mail.Message.RecipientType.TO, null,
					java.util.Set.of(DeliveryStatusNotification.NotifyOption.NEVER));
			assertThatThrownBy(() -> mailer.getEmailGovernance().produceEmailApplyingDefaultsAndOverrides(
					ordinary.emailBuilder().startingBlank().withRecipients(conflicting).buildEmail()))
					.hasMessageContaining("locked.defaults.delivery.status.notification.notify")
					.hasMessageContaining("Email or a recipient requests different delivery-notification events")
					.hasMessageContaining("inherit the locked events").hasMessageContaining("change this lock");
		}
	}

	@Test
	void anIncompleteProtectionLockExplainsTheMissingKeyOrCertificateConfiguration() throws Exception {
		try (Mailer mailer = locked(Map.of("dkim.signing.algorithm", "SHA256_WITH_RSA")).mailerBuilder().withTransportModeLoggingOnly(true).buildMailer()) {
			assertThatThrownBy(() -> mailer.getEmailGovernance().produceEmailApplyingDefaultsAndOverrides(ordinary.emailBuilder().startingBlank().buildEmail()))
					.hasMessageContaining("locked.dkim.signing.algorithm").hasMessageContaining("Email and its configured defaults")
					.hasMessageContaining("missing key or certificate").hasMessageContaining("Supply the matching signing/encryption configuration")
					.hasMessageContaining("remove this incomplete lock");
		}
	}

	@Test
	void operationalDefaultsDoNotBecomeExactContentRequirements() throws Exception {
		final byte[] bytes = "From: sender@example.org\r\nTo: receiver@example.org\r\nSubject: Original\r\n\r\nbody\r\n".getBytes(StandardCharsets.US_ASCII);
		try (Mailer mailer = locked(Map.of("defaults.sessiontimeoutmillis", "1000", "defaults.connectionpool.maxsize", "2"))
				.mailerBuilder().withTransportModeLoggingOnly(true).buildMailer()) {
			assertThat(mailer.rehearse(ordinary.emailBuilder().startingFromExactEml(bytes).withEnvelopeRecipients("receiver@example.org")
					.buildEmail()).getEmlBytes()).containsExactly(bytes);
		}
	}

	@Test
	void smimeLocksApplyAfterSuppressionAndRejectPerRecipientCertificateChanges() throws Exception {
		final String certificate = "src/test/resources/pkcs12/smime_test_user.pem.standard.crt";
		final SimpleJavaMail factory = locked(Map.of("smime.encryption.certificate", certificate));
		try (Mailer mailer = factory.mailerBuilder().withTransportModeLoggingOnly(true).buildMailer()) {
			final Email protectedEmail = mailer.getEmailGovernance().produceEmailApplyingDefaultsAndOverrides(
					ordinary.emailBuilder().startingBlank().ignoringDefaults().buildEmail());
			assertThat(protectedEmail.getSmimeEncryptionConfig().getX509Certificate()).isNotNull();
			final java.security.cert.X509Certificate other = org.simplejavamail.api.email.config.SmimeEncryptionConfig.builder()
					.x509Certificate("src/test/resources/pkcs12/ca.crt").build().getX509Certificate();
			final Email conflicting = ordinary.emailBuilder().startingBlank()
					.withRecipients(new Recipient(null, "receiver@example.org", jakarta.mail.Message.RecipientType.TO, other)).buildEmail();
			assertThatThrownBy(() -> mailer.getEmailGovernance().produceEmailApplyingDefaultsAndOverrides(conflicting))
					.hasMessageContaining("locked.smime.encryption.certificate");
		}
	}

	@Test
	void aReplacementTemplateStillSkipsOrdinaryKeyFilesWhenOtherSettingsAreLocked() throws Exception {
		final SimpleJavaMail factory = SimpleJavaMail.withConfig(ConfigLoader.builder().withMap(Map.of(
				"simplejavamail.locked.smtp.host", "relay.example.org",
				"simplejavamail.locked.defaults.subject", "Company mail",
				"simplejavamail.dkim.signing.private_key_file_or_data", "file:missing-ordinary-key.der",
				"simplejavamail.dkim.signing.selector", "ordinary",
				"simplejavamail.dkim.signing.signing_domain", "example.org")).load());
		final Email replacement = ordinary.emailBuilder().startingBlank().buildEmail();
		try (Mailer mailer = factory.mailerBuilder().withEmailDefaults(replacement).withTransportModeLoggingOnly(true).buildMailer()) {
			final Email prepared = mailer.getEmailGovernance().produceEmailApplyingDefaultsAndOverrides(replacement);
			assertThat(prepared.getSubject()).isEqualTo("Company mail");
			assertThat(prepared.getDkimConfig()).isNull();
		}
	}

	static SimpleJavaMail locked(final Map<String, String> values) {
		final Properties properties = new Properties();
		values.forEach((key, value) -> properties.setProperty("simplejavamail.locked." + key, value));
		return SimpleJavaMail.withConfig(ConfigLoader.builder().withProperties(properties).load());
	}
}
