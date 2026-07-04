package org.simplejavamail.mailer.internal;

import jakarta.mail.Session;
import lombok.val;
import org.jetbrains.annotations.NotNull;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.simplejavamail.MailException;
import org.simplejavamail.api.email.Email;
import org.simplejavamail.api.email.config.DkimConfig;
import org.simplejavamail.api.email.config.SmimeSigningConfig;
import org.simplejavamail.api.mailer.Mailer;
import org.simplejavamail.api.mailer.config.EmailGovernance;
import org.simplejavamail.api.mailer.config.ProxyConfig;
import org.simplejavamail.config.ConfigLoader;
import org.simplejavamail.email.EmailBuilder;
import org.simplejavamail.mailer.MailerBuilder;
import testutil.ConfigLoaderTestHelper;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Properties;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.simplejavamail.api.mailer.config.TransportStrategy.SMTP;
import static org.simplejavamail.api.mailer.config.TransportStrategy.SMTPS;
import static org.simplejavamail.api.mailer.config.TransportStrategy.SMTP_OAUTH2;
import static org.simplejavamail.api.mailer.config.TransportStrategy.SMTP_TLS;
import static org.simplejavamail.config.ConfigLoader.Property.DEFAULT_FROM_ADDRESS;
import static org.simplejavamail.config.ConfigLoader.Property.DEFAULT_SUBJECT;
import static org.simplejavamail.config.ConfigLoader.Property.DKIM_EXCLUDED_HEADERS_FROM_DEFAULT_SIGNING_LIST;
import static org.simplejavamail.config.ConfigLoader.Property.DKIM_PRIVATE_KEY_FILE_OR_DATA;
import static org.simplejavamail.config.ConfigLoader.Property.DKIM_SELECTOR;
import static org.simplejavamail.config.ConfigLoader.Property.DKIM_SIGNING_DOMAIN;
import static org.simplejavamail.mailer.internal.EmailGovernanceImpl.NO_GOVERNANCE;
import static org.simplejavamail.util.TestDataHelper.loadPkcs12KeyStore;
import static testutil.EmailHelper.createDummyOperationalConfig;

public class MailerImplTest {
	
	private Session session;
	
	private static final List<String> EMPTY_LIST = Collections.emptyList();
	
	@BeforeEach
	public void setup() {
		session = Session.getInstance(new Properties());
	}
	
	@Test
	public void trustAllHosts_PLAIN() {
		new MailerImpl(null, SMTP, NO_GOVERNANCE(), createEmptyProxyConfig(), session, createDummyOperationalConfig(EMPTY_LIST, true, false));
		assertThat(session.getProperties().getProperty("mail.smtp.ssl.trust")).isEqualTo("*");
		assertThat(session.getProperties().getProperty("mail.smtp.ssl.checkserveridentity")).isEqualTo("false");
		assertThat(session.getProperties().getProperty("mail.smtps.ssl.checkserveridentity")).isNull();
		new MailerImpl(null, SMTP, NO_GOVERNANCE(), createEmptyProxyConfig(), session, createDummyOperationalConfig(EMPTY_LIST, false, true));
		assertThat(session.getProperties().getProperty("mail.smtp.ssl.trust")).isNull();
		assertThat(session.getProperties().getProperty("mail.smtp.ssl.checkserveridentity")).isEqualTo("true");
		assertThat(session.getProperties().getProperty("mail.smtps.ssl.checkserveridentity")).isNull();
	}
	
	@Test
	public void trustAllHosts_SMTPS() {
		ProxyConfig proxyBypassingMock = mock(ProxyConfig.class);
		when(proxyBypassingMock.requiresProxy()).thenReturn(false);
		new MailerImpl(null, SMTPS, NO_GOVERNANCE(), proxyBypassingMock, session, createDummyOperationalConfig(EMPTY_LIST, true, false));
		assertThat(session.getProperties().getProperty("mail.smtps.ssl.trust")).isEqualTo("*");
		assertThat(session.getProperties().getProperty("mail.smtp.ssl.checkserveridentity")).isNull();
		assertThat(session.getProperties().getProperty("mail.smtps.ssl.checkserveridentity")).isEqualTo("false");
		new MailerImpl(null, SMTPS, NO_GOVERNANCE(), proxyBypassingMock, session, createDummyOperationalConfig(EMPTY_LIST, false, true));
		assertThat(session.getProperties().getProperty("mail.smtps.ssl.trust")).isNull();
		assertThat(session.getProperties().getProperty("mail.smtp.ssl.checkserveridentity")).isNull();
		assertThat(session.getProperties().getProperty("mail.smtps.ssl.checkserveridentity")).isEqualTo("true");
	}

	@Test
	public void trustAllHosts_SMTP_TLS() {
		new MailerImpl(null, SMTP_TLS, NO_GOVERNANCE(), createEmptyProxyConfig(), session, createDummyOperationalConfig(EMPTY_LIST, true, false));
		assertThat(session.getProperties().getProperty("mail.smtp.ssl.trust")).isEqualTo("*");
		assertThat(session.getProperties().getProperty("mail.smtp.ssl.checkserveridentity")).isEqualTo("false");
		assertThat(session.getProperties().getProperty("mail.smtps.ssl.checkserveridentity")).isNull();
		new MailerImpl(null, SMTP_TLS, NO_GOVERNANCE(), createEmptyProxyConfig(), session, createDummyOperationalConfig(EMPTY_LIST, false, true));
		assertThat(session.getProperties().getProperty("mail.smtps.ssl.trust")).isNull();
		assertThat(session.getProperties().getProperty("mail.smtp.ssl.checkserveridentity")).isEqualTo("true");
		assertThat(session.getProperties().getProperty("mail.smtps.ssl.checkserveridentity")).isNull();
	}

	@Test
	public void setPropertOAuth2Property() {
		val serverConfig = new ServerConfigImpl("hosty", 10, "usey", "passey", null, null);
		val mailer = new MailerImpl(serverConfig, SMTP_OAUTH2, NO_GOVERNANCE(), createEmptyProxyConfig(), null, createDummyOperationalConfig(EMPTY_LIST, true, false));
		assertThat(mailer.getSession().getProperties().getProperty("mail.smtp.auth.mechanisms")).isEqualTo("XOAUTH2");
	}

	@Test
	public void checkForMissingOAuth2Token() {
		val serverConfig = new ServerConfigImpl("hosty", 10, "usey", null, null, null);
		assertThatThrownBy(() -> new MailerImpl(serverConfig, SMTP_OAUTH2, NO_GOVERNANCE(), createEmptyProxyConfig(), null, createDummyOperationalConfig(EMPTY_LIST, true, false)))
				.hasMessage("TransportStrategy is OAUTH2 but no OAUTH2 token provided as password")
				.isInstanceOf(MailException.class);
	}
	
	@Test
	public void trustHosts() {
		new MailerImpl(null, SMTP, NO_GOVERNANCE(), createEmptyProxyConfig(), session, createDummyOperationalConfig(asList(), false, false));
		assertThat(session.getProperties().getProperty("mail.smtp.ssl.trust")).isNull();
		assertThat(session.getProperties().getProperty("mail.smtp.ssl.checkserveridentity")).isEqualTo("false");
		assertThat(session.getProperties().getProperty("mail.smtps.ssl.checkserveridentity")).isNull();
		new MailerImpl(null, SMTP, NO_GOVERNANCE(), createEmptyProxyConfig(), session, createDummyOperationalConfig(asList("a"), false, false));
		assertThat(session.getProperties().getProperty("mail.smtp.ssl.trust")).isEqualTo("a");
		assertThat(session.getProperties().getProperty("mail.smtp.ssl.checkserveridentity")).isEqualTo("false");
		assertThat(session.getProperties().getProperty("mail.smtps.ssl.checkserveridentity")).isNull();
		new MailerImpl(null, SMTP, NO_GOVERNANCE(), createEmptyProxyConfig(), session, createDummyOperationalConfig(asList("a", "b"), false, false));
		assertThat(session.getProperties().getProperty("mail.smtp.ssl.trust")).isEqualTo("a b");
		assertThat(session.getProperties().getProperty("mail.smtp.ssl.checkserveridentity")).isEqualTo("false");
		assertThat(session.getProperties().getProperty("mail.smtps.ssl.checkserveridentity")).isNull();
		new MailerImpl(null, SMTP, NO_GOVERNANCE(), createEmptyProxyConfig(), session, createDummyOperationalConfig(asList("a", "b", "c"), false, true));
		assertThat(session.getProperties().getProperty("mail.smtp.ssl.trust")).isEqualTo("a b c");
		assertThat(session.getProperties().getProperty("mail.smtp.ssl.checkserveridentity")).isEqualTo("true");
		assertThat(session.getProperties().getProperty("mail.smtps.ssl.checkserveridentity")).isNull();
	}

	@Test
	public void testSignWithSmime_WithConfigObject() {
		final Email emailWithDefaultPkcs12KeyStoreDefault = EmailBuilder.startingBlank()
				.signWithSmime(SmimeSigningConfig.builder()
						.pkcs12Config(loadPkcs12KeyStore())
						.build())
				.buildEmail();

		final EmailGovernance emailGovernance = new EmailGovernanceImpl(null, emailWithDefaultPkcs12KeyStoreDefault, null, null);
		final Mailer mailer = new MailerImpl(null, SMTP, emailGovernance, createEmptyProxyConfig(), session, createDummyOperationalConfig(EMPTY_LIST, true, false));

		val actual = mailer.getEmailGovernance().produceEmailApplyingDefaultsAndOverrides(null).getSmimeSigningConfig();

		assertThat(actual).isNotNull();
		assertThat(actual.getPkcs12Config()).isNotNull();
		assertThat(actual.getPkcs12Config().getPkcs12StoreData()).isNotNull();
		assertThat(actual.getPkcs12Config().getStorePassword()).isEqualTo("letmein".toCharArray());
		assertThat(actual.getPkcs12Config().getKeyAlias()).isEqualTo("smime_test_user_alias_rsa");
		assertThat(actual.getPkcs12Config().getKeyPassword()).isEqualTo("letmein".toCharArray());
	}

	@Test
	public void testDefaultDkimSigning_WithConfigObjectPreservesConfigDefaults() throws Exception {
		final Properties properties = new Properties();
		properties.setProperty(DEFAULT_FROM_ADDRESS.key(), "default@domain.com");
		properties.setProperty(DEFAULT_SUBJECT.key(), "default subject");

		try {
			ConfigLoader.loadProperties(properties, false);

			final DkimConfig dkimConfig = dkimConfig("java-default.com", "java-default");
			final Mailer mailer = MailerBuilder
					.withSMTPServer("host", 25, null, null)
					.withDefaultDkimSigning(dkimConfig)
					.buildMailer();

			final Email resolved = mailer.getEmailGovernance().produceEmailApplyingDefaultsAndOverrides(EmailBuilder.startingBlank().buildEmail());

			assertThat(resolved.getFromRecipient().getAddress()).isEqualTo("default@domain.com");
			assertThat(resolved.getSubject()).isEqualTo("default subject");
			assertThat(resolved.getDkimConfig()).isEqualTo(dkimConfig);
		} finally {
			ConfigLoaderTestHelper.clearConfigProperties();
		}
	}

	@Test
	public void testDefaultDkimSigning_UserEmailTakesPrecedence() {
		ConfigLoaderTestHelper.clearConfigProperties();

		final DkimConfig defaultDkimConfig = dkimConfig("java-default.com", "java-default");
		final DkimConfig userDkimConfig = dkimConfig("user.com", "user");
		final Mailer mailer = MailerBuilder
				.withSMTPServer("host", 25, null, null)
				.withDefaultDkimSigning(defaultDkimConfig)
				.buildMailer();
		final Email userEmail = EmailBuilder.startingBlank()
				.from("from@user.com")
				.signWithDomainKey(userDkimConfig)
				.buildEmail();

		final Email resolved = mailer.getEmailGovernance().produceEmailApplyingDefaultsAndOverrides(userEmail);

		assertThat(resolved.getDkimConfig()).isEqualTo(userDkimConfig);
	}

	@Test
	public void testDefaultDkimSigning_WithInlineArguments() {
		ConfigLoaderTestHelper.clearConfigProperties();

		final Mailer mailer = MailerBuilder
				.withSMTPServer("host", 25, null, null)
				.withDefaultDkimSigning("key".getBytes(), "inline-default.com", "inline-default", Collections.singleton("Reply-To"))
				.buildMailer();
		final Email userEmail = EmailBuilder.startingBlank()
				.from("from@inline-default.com")
				.buildEmail();

		final DkimConfig resolvedDkimConfig = mailer.getEmailGovernance().produceEmailApplyingDefaultsAndOverrides(userEmail).getDkimConfig();

		assertThat(resolvedDkimConfig).isEqualTo(DkimConfig.builder()
				.dkimPrivateKeyData("key".getBytes())
				.dkimSigningDomain("inline-default.com")
				.dkimSelector("inline-default")
				.excludedHeadersFromDkimDefaultSigningList("Reply-To")
				.build());
	}

	@Test
	public void testClearDefaultDkimSigning_SuppressesPropertyDefaultOnly() throws Exception {
		final Properties properties = new Properties();
		properties.setProperty(DEFAULT_FROM_ADDRESS.key(), "default@domain.com");
		properties.setProperty(DEFAULT_SUBJECT.key(), "default subject");
		properties.setProperty(DKIM_PRIVATE_KEY_FILE_OR_DATA.key(), "src/test/resources/dkim/dkim_dummy_key.der");
		properties.setProperty(DKIM_SIGNING_DOMAIN.key(), "property-default.com");
		properties.setProperty(DKIM_SELECTOR.key(), "property-default");
		properties.setProperty(DKIM_EXCLUDED_HEADERS_FROM_DEFAULT_SIGNING_LIST.key(), "Reply-To");

		try {
			ConfigLoader.loadProperties(properties, false);

			final Mailer mailer = MailerBuilder
					.withSMTPServer("host", 25, null, null)
					.clearDefaultDkimSigning()
					.buildMailer();
			final Email resolved = mailer.getEmailGovernance().produceEmailApplyingDefaultsAndOverrides(EmailBuilder.startingBlank().buildEmail());

			assertThat(resolved.getFromRecipient().getAddress()).isEqualTo("default@domain.com");
			assertThat(resolved.getSubject()).isEqualTo("default subject");
			assertThat(resolved.getDkimConfig()).isNull();
		} finally {
			ConfigLoaderTestHelper.clearConfigProperties();
		}
	}

	@NotNull
	private List<String> asList(String... args) {
		return Arrays.asList(args);
	}

	private DkimConfig dkimConfig(final String signingDomain, final String selector) {
		return DkimConfig.builder()
				.dkimPrivateKeyData("key-" + selector)
				.dkimSigningDomain(signingDomain)
				.dkimSelector(selector)
				.build();
	}

	@NotNull
	private ProxyConfig createEmptyProxyConfig() {
		return new ProxyConfigImpl(null, null, null, null, null);
	}
}
