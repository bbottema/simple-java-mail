package org.simplejavamail.mailer.internal;

import org.junit.jupiter.api.Test;
import org.simplejavamail.api.SimpleJavaMail;
import org.simplejavamail.api.email.Email;
import org.simplejavamail.api.email.Recipient;
import org.simplejavamail.api.email.config.DkimConfig;
import org.simplejavamail.api.email.config.SmimeEncryptionConfig;
import org.simplejavamail.api.mailer.Mailer;
import org.simplejavamail.api.mailer.MailerRegularBuilder;
import org.simplejavamail.config.ConfigLoader;
import org.simplejavamail.config.SimpleJavaMailConfig;
import org.simplejavamail.mailer.MailCompletenessException;
import testutil.ConfigLoaderTestHelper;

import java.security.cert.X509Certificate;
import java.util.Properties;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.stream.IntStream;

import static jakarta.mail.Message.RecipientType.TO;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.simplejavamail.config.ConfigLoader.Property.DEFAULT_FROM_ADDRESS;
import static org.simplejavamail.config.ConfigLoader.Property.DEFAULT_SUBJECT;
import static org.simplejavamail.config.ConfigLoader.Property.DKIM_PRIVATE_KEY_FILE_OR_DATA;
import static org.simplejavamail.config.ConfigLoader.Property.DKIM_SELECTOR;
import static org.simplejavamail.config.ConfigLoader.Property.DKIM_SIGNING_DOMAIN;
import static org.simplejavamail.internal.config.EmailProperty.DKIM_SIGNING_CONFIG;

class EmailGovernancePolicyTest {

	private final SimpleJavaMail mail = SimpleJavaMail.withConfig(ConfigLoaderTestHelper.emptyConfig());

	@Test
	void signingTemplatesDoNotNeedAFromAddress() throws Exception {
		final DkimConfig signing = signingConfig("template");
		final Email template = mail.emailBuilder().startingBlank().signWithDomainKey(signing).buildEmail();
		assertThat(template.getFromRecipient()).isNull();
		assertThat(template.getDkimConfig()).isSameAs(signing);
		try (Mailer mailer = mail.mailerBuilder().withTransportModeLoggingOnly(true).withEmailDefaults(template).buildMailer()) {
			final Email resolved = mailer.getEmailGovernance().produceEmailApplyingDefaultsAndOverrides(
					mail.emailBuilder().startingBlank().from("sender@example.org").buildEmail());
			assertThat(resolved.getFromRecipient().getAddress()).isEqualTo("sender@example.org");
			assertThat(resolved.getDkimConfig()).isSameAs(signing);
		}
		assertThat(template.getFromRecipient()).isNull();
	}

	@Test
	void dkimUsesOrdinaryValuePrecedenceAndSuppression() {
		final Email defaults = signingTemplate("default");
		final Email overrides = signingTemplate("override");
		final Email submitted = signingTemplate("submitted");
		final EmailGovernanceImpl governance = new EmailGovernanceImpl(null, defaults, overrides, null);

		assertThat(governance.produceEmailApplyingDefaultsAndOverrides(submitted).getDkimConfig()).isSameAs(overrides.getDkimConfig());
		assertThat(governance.produceEmailApplyingDefaultsAndOverrides(mail.emailBuilder().copying(submitted)
				.ignoringOverrides().buildEmail()).getDkimConfig()).isSameAs(submitted.getDkimConfig());
		assertThat(governance.produceEmailApplyingDefaultsAndOverrides(mail.emailBuilder().copying(submitted)
				.dontApplyOverrideValueFor(DKIM_SIGNING_CONFIG).buildEmail()).getDkimConfig()).isSameAs(submitted.getDkimConfig());
		assertThat(governance.produceEmailApplyingDefaultsAndOverrides(mail.emailBuilder().startingBlank()
				.ignoringOverrides().buildEmail()).getDkimConfig()).isSameAs(defaults.getDkimConfig());
		assertThat(governance.produceEmailApplyingDefaultsAndOverrides(mail.emailBuilder().startingBlank()
				.ignoringOverrides().ignoringDefaults().buildEmail()).getDkimConfig()).isNull();
		assertThat(governance.produceEmailApplyingDefaultsAndOverrides(mail.emailBuilder().startingBlank()
				.dontApplyOverrideValueFor(DKIM_SIGNING_CONFIG).dontApplyDefaultValueFor(DKIM_SIGNING_CONFIG)
				.buildEmail()).getDkimConfig()).isNull();
		assertThat(submitted.getDkimConfig()).isEqualTo(signingConfig("submitted"));
	}

	@Test
	void suppliedTemplateReplacesPropertyDefaultsAndClearRestoresThem() throws Exception {
		final SimpleJavaMail configured = SimpleJavaMail.withConfig(propertyDefaults());
		final MailerRegularBuilder<?> builder = configured.mailerBuilder().withTransportModeLoggingOnly(true)
				.withEmailDefaults(configured.emailBuilder().startingBlank().withSubject("explicit template").buildEmail());
		try (Mailer explicit = builder.buildMailer(); Mailer restored = builder.clearEmailDefaults().buildMailer()) {
			final Email explicitDefaults = explicit.getEmailGovernance().produceEmailApplyingDefaultsAndOverrides(null);
			assertThat(explicitDefaults.getSubject()).isEqualTo("explicit template");
			assertThat(explicitDefaults.getFromRecipient()).isNull();
			assertThat(explicitDefaults.getDkimConfig()).isNull();
			final Email restoredDefaults = restored.getEmailGovernance().produceEmailApplyingDefaultsAndOverrides(null);
			assertThat(restoredDefaults.getSubject()).isEqualTo("property subject");
			assertThat(restoredDefaults.getFromRecipient().getAddress()).isEqualTo("property@example.org");
			assertThat(restoredDefaults.getDkimConfig().getDkimSelector()).isEqualTo("property");
		}
	}

	@Test
	void materializedTemplateCanReplaceOrOmitDkimWithoutLosingOtherPropertyDefaults() throws Exception {
		final SimpleJavaMail configured = SimpleJavaMail.withConfig(propertyDefaults());
		final Email propertyTemplate = configured.emailBuilder().startingBlank().buildEmailCompletedWithDefaultsAndOverrides();
		final Email signingTemplate = configured.emailBuilder().copying(propertyTemplate)
				.signWithDomainKey(signingConfig("java")).buildEmail();
		final Email unsignedTemplate = configured.emailBuilder().copying(propertyTemplate).clearDkim().buildEmail();

		try (Mailer signing = configured.mailerBuilder().withTransportModeLoggingOnly(true).withEmailDefaults(signingTemplate).buildMailer();
				Mailer unsigned = configured.mailerBuilder().withTransportModeLoggingOnly(true).withEmailDefaults(unsignedTemplate).buildMailer()) {
			final Email signed = signing.getEmailGovernance().produceEmailApplyingDefaultsAndOverrides(null);
			final Email notSigned = unsigned.getEmailGovernance().produceEmailApplyingDefaultsAndOverrides(null);
			assertThat(signed.getSubject()).isEqualTo("property subject");
			assertThat(signed.getDkimConfig()).isEqualTo(signingConfig("java"));
			assertThat(notSigned.getSubject()).isEqualTo("property subject");
			assertThat(notSigned.getFromRecipient().getAddress()).isEqualTo("property@example.org");
			assertThat(notSigned.getDkimConfig()).isNull();
			assertThat(unsigned.getEmailGovernance().produceEmailApplyingDefaultsAndOverrides(signingTemplate).getDkimConfig())
					.isSameAs(signingTemplate.getDkimConfig());
		}
		assertThat(propertyTemplate.getDkimConfig().getDkimSelector()).isEqualTo("property");
		assertThat(configured.getConfig().getStringProperty(DKIM_SELECTOR)).isEqualTo("property");
	}

	@Test
	void explicitTemplateSkipsPropertyMaterialButMaterializingItDoesNot() throws Exception {
		final Properties properties = new Properties();
		properties.setProperty(DEFAULT_SUBJECT.key(), "property subject");
		properties.setProperty(DKIM_PRIVATE_KEY_FILE_OR_DATA.key(), "base64:invalid!");
		properties.setProperty(DKIM_SIGNING_DOMAIN.key(), "example.org");
		properties.setProperty(DKIM_SELECTOR.key(), "property");
		final SimpleJavaMail configured = SimpleJavaMail.withConfig(ConfigLoader.builder().withProperties(properties).load());
		final Email replacement = configured.emailBuilder().startingBlank().withSubject("explicit template").buildEmail();
		try (Mailer mailer = configured.mailerBuilder().withTransportModeLoggingOnly(true).withEmailDefaults(replacement).buildMailer()) {
			final Email resolved = mailer.getEmailGovernance().produceEmailApplyingDefaultsAndOverrides(null);
			assertThat(resolved.getSubject()).isEqualTo("explicit template");
			assertThat(resolved.getDkimConfig()).isNull();
		}
		assertThatThrownBy(() -> configured.emailBuilder().startingBlank().buildEmailCompletedWithDefaultsAndOverrides())
				.isInstanceOf(IllegalArgumentException.class).hasMessageContaining("Invalid Base64 DKIM private key data");
	}

	@Test
	void clearingLocalSigningRestoresDefaultsUnlessExplicitlySuppressed() {
		final EmailGovernanceImpl governance = new EmailGovernanceImpl(null, signingTemplate("default"), null, null);
		final Email cleared = mail.emailBuilder().copying(signingTemplate("provided")).clearDkim().buildEmail();
		assertThat(governance.produceEmailApplyingDefaultsAndOverrides(cleared).getDkimConfig()).isEqualTo(signingConfig("default"));
		assertThat(governance.produceEmailApplyingDefaultsAndOverrides(mail.emailBuilder().copying(cleared)
				.dontApplyDefaultValueFor(DKIM_SIGNING_CONFIG).buildEmail()).getDkimConfig()).isNull();
	}

	@Test
	void missingSenderIsStillRejectedBeforeSending() throws Exception {
		try (Mailer mailer = mail.mailerBuilder().withTransportModeLoggingOnly(true).withEmailDefaults(signingTemplate("default")).buildMailer()) {
			final Email incomplete = mail.emailBuilder().startingBlank()
					.withRecipients(new Recipient(null, "receiver@example.org", TO, null)).buildEmail();
			assertThatThrownBy(() -> mailer.sync().sendMail(incomplete)).hasMessageContaining("missing sender");
			assertThatThrownBy(() -> mailer.async().sendMail(incomplete).getCompletion().join())
					.hasCauseInstanceOf(MailCompletenessException.class).hasMessageContaining("missing sender");
		}
	}

	@Test
	void recipientCertificateIsNotRewrittenByAnEmailOverride() {
		final X509Certificate recipientCertificate = mock(X509Certificate.class);
		final X509Certificate overrideCertificate = mock(X509Certificate.class);
		final Email overrides = mail.emailBuilder().startingBlank()
				.encryptWithSmime(SmimeEncryptionConfig.builder().x509Certificate(overrideCertificate).build()).buildEmail();
		final Email submitted = mail.emailBuilder().startingBlank()
				.withRecipients(new Recipient(null, "receiver@example.org", TO, recipientCertificate)).buildEmail();
		final Email resolved = new EmailGovernanceImpl(null, null, overrides, null).produceEmailApplyingDefaultsAndOverrides(submitted);
		assertThat(resolved.getSmimeEncryptionConfig().getX509Certificate()).isSameAs(overrideCertificate);
		assertThat(resolved.getRecipients().get(0).getSmimeCertificate()).isSameAs(recipientCertificate);
		assertThat(submitted.getSmimeEncryptionConfig()).isNull();
	}

	@Test
	void sharedEmailKeepsIndependentMailerPoliciesDuringConcurrentResolution() throws Exception {
		final Email submitted = mail.emailBuilder().startingBlank().withSubject("unchanged").buildEmail();
		final Email defaultsA = signingTemplate("first");
		final Email defaultsB = signingTemplate("second");
		final EmailGovernanceImpl first = new EmailGovernanceImpl(null, defaultsA, null, null);
		final EmailGovernanceImpl second = new EmailGovernanceImpl(null, defaultsB, null, null);
		final ExecutorService workers = Executors.newFixedThreadPool(4);
		try {
			final CompletableFuture<?>[] resolutions = IntStream.range(0, 60).mapToObj(index -> CompletableFuture.runAsync(() -> {
				final boolean useFirst = index % 2 == 0;
				final Email resolved = (useFirst ? first : second).produceEmailApplyingDefaultsAndOverrides(submitted);
				assertThat(resolved.getDkimConfig()).isSameAs((useFirst ? defaultsA : defaultsB).getDkimConfig());
				assertThat(resolved.getSubject()).isEqualTo("unchanged");
			}, workers)).toArray(CompletableFuture<?>[]::new);
			CompletableFuture.allOf(resolutions).get(10, TimeUnit.SECONDS);
		} finally {
			workers.shutdownNow();
		}
		assertThat(submitted.getDkimConfig()).isNull();
		assertThat(defaultsA.getDkimConfig()).isEqualTo(signingConfig("first"));
		assertThat(defaultsB.getDkimConfig()).isEqualTo(signingConfig("second"));
	}

	private Email signingTemplate(final String selector) {
		return mail.emailBuilder().startingBlank().signWithDomainKey(signingConfig(selector)).buildEmail();
	}

	private static DkimConfig signingConfig(final String selector) {
		return DkimConfig.builder().dkimPrivateKeyData("key-" + selector).dkimSigningDomain("example.org").dkimSelector(selector).build();
	}

	private static SimpleJavaMailConfig propertyDefaults() {
		final Properties properties = new Properties();
		properties.setProperty(DEFAULT_FROM_ADDRESS.key(), "property@example.org");
		properties.setProperty(DEFAULT_SUBJECT.key(), "property subject");
		properties.setProperty(DKIM_PRIVATE_KEY_FILE_OR_DATA.key(), "src/test/resources/dkim/dkim_dummy_key.der");
		properties.setProperty(DKIM_SIGNING_DOMAIN.key(), "example.org");
		properties.setProperty(DKIM_SELECTOR.key(), "property");
		return ConfigLoader.builder().withProperties(properties).load();
	}
}
