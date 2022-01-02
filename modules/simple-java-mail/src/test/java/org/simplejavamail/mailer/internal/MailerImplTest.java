package org.simplejavamail.mailer.internal;

import jakarta.mail.Session;
import org.jetbrains.annotations.NotNull;
import org.junit.Before;
import org.junit.Test;
import org.simplejavamail.api.mailer.Mailer;
import org.simplejavamail.api.mailer.config.EmailGovernance;
import org.simplejavamail.api.mailer.config.ProxyConfig;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Properties;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.simplejavamail.api.mailer.config.TransportStrategy.SMTP;
import static org.simplejavamail.api.mailer.config.TransportStrategy.SMTPS;
import static org.simplejavamail.api.mailer.config.TransportStrategy.SMTP_TLS;
import static org.simplejavamail.util.TestDataHelper.loadPkcs12KeyStore;
import static testutil.EmailHelper.createDummyOperationalConfig;

public class MailerImplTest {
	
	private Session session;
	
	private static final List<String> EMPTY_LIST = Collections.emptyList();
	
	@Before
	public void setup() {
		session = Session.getInstance(new Properties());
	}
	
	@Test
	public void trustAllHosts_PLAIN() {
		new MailerImpl(null, SMTP, createDummyEmailGovernance(), createEmptyProxyConfig(), session, createDummyOperationalConfig(EMPTY_LIST, true, false));
		assertThat(session.getProperties().getProperty("mail.smtp.ssl.trust")).isEqualTo("*");
		assertThat(session.getProperties().getProperty("mail.smtp.ssl.checkserveridentity")).isNull();
		assertThat(session.getProperties().getProperty("mail.smtps.ssl.checkserveridentity")).isNull();
		new MailerImpl(null, SMTP, createDummyEmailGovernance(), createEmptyProxyConfig(), session, createDummyOperationalConfig(EMPTY_LIST, false, true));
		assertThat(session.getProperties().getProperty("mail.smtp.ssl.trust")).isNull();
		assertThat(session.getProperties().getProperty("mail.smtp.ssl.checkserveridentity")).isNull();
		assertThat(session.getProperties().getProperty("mail.smtps.ssl.checkserveridentity")).isNull();
	}
	
	@Test
	public void trustAllHosts_SMTPS() {
		ProxyConfig proxyBypassingMock = mock(ProxyConfig.class);
		when(proxyBypassingMock.requiresProxy()).thenReturn(false);
		new MailerImpl(null, SMTPS, createDummyEmailGovernance(), proxyBypassingMock, session, createDummyOperationalConfig(EMPTY_LIST, true, false));
		assertThat(session.getProperties().getProperty("mail.smtps.ssl.trust")).isEqualTo("*");
		assertThat(session.getProperties().getProperty("mail.smtp.ssl.checkserveridentity")).isNull();
		assertThat(session.getProperties().getProperty("mail.smtps.ssl.checkserveridentity")).isEqualTo("false");
		new MailerImpl(null, SMTPS, createDummyEmailGovernance(), proxyBypassingMock, session, createDummyOperationalConfig(EMPTY_LIST, false, true));
		assertThat(session.getProperties().getProperty("mail.smtps.ssl.trust")).isNull();
		assertThat(session.getProperties().getProperty("mail.smtp.ssl.checkserveridentity")).isNull();
		assertThat(session.getProperties().getProperty("mail.smtps.ssl.checkserveridentity")).isEqualTo("true");
	}

	@Test
	public void trustAllHosts_SMTP_TLS() {
		new MailerImpl(null, SMTP_TLS, createDummyEmailGovernance(), createEmptyProxyConfig(), session, createDummyOperationalConfig(EMPTY_LIST, true, false));
		assertThat(session.getProperties().getProperty("mail.smtp.ssl.trust")).isEqualTo("*");
		assertThat(session.getProperties().getProperty("mail.smtp.ssl.checkserveridentity")).isEqualTo("false");
		assertThat(session.getProperties().getProperty("mail.smtps.ssl.checkserveridentity")).isNull();
		new MailerImpl(null, SMTP_TLS, createDummyEmailGovernance(), createEmptyProxyConfig(), session, createDummyOperationalConfig(EMPTY_LIST, false, true));
		assertThat(session.getProperties().getProperty("mail.smtps.ssl.trust")).isNull();
		assertThat(session.getProperties().getProperty("mail.smtp.ssl.checkserveridentity")).isEqualTo("true");
		assertThat(session.getProperties().getProperty("mail.smtps.ssl.checkserveridentity")).isNull();
	}
	
	@Test
	public void trustHosts() {
		new MailerImpl(null, SMTP, createDummyEmailGovernance(), createEmptyProxyConfig(), session, createDummyOperationalConfig(asList(), false, false));
		assertThat(session.getProperties().getProperty("mail.smtp.ssl.trust")).isNull();
		assertThat(session.getProperties().getProperty("mail.smtp.ssl.checkserveridentity")).isNull();
		assertThat(session.getProperties().getProperty("mail.smtps.ssl.checkserveridentity")).isNull();
		new MailerImpl(null, SMTP, createDummyEmailGovernance(), createEmptyProxyConfig(), session, createDummyOperationalConfig(asList("a"), false, false));
		assertThat(session.getProperties().getProperty("mail.smtp.ssl.trust")).isEqualTo("a");
		assertThat(session.getProperties().getProperty("mail.smtp.ssl.checkserveridentity")).isNull();
		assertThat(session.getProperties().getProperty("mail.smtps.ssl.checkserveridentity")).isNull();
		new MailerImpl(null, SMTP, createDummyEmailGovernance(), createEmptyProxyConfig(), session, createDummyOperationalConfig(asList("a", "b"), false, false));
		assertThat(session.getProperties().getProperty("mail.smtp.ssl.trust")).isEqualTo("a b");
		assertThat(session.getProperties().getProperty("mail.smtp.ssl.checkserveridentity")).isNull();
		assertThat(session.getProperties().getProperty("mail.smtps.ssl.checkserveridentity")).isNull();
		new MailerImpl(null, SMTP, createDummyEmailGovernance(), createEmptyProxyConfig(), session, createDummyOperationalConfig(asList("a", "b", "c"), false, true));
		assertThat(session.getProperties().getProperty("mail.smtp.ssl.trust")).isEqualTo("a b c");
		assertThat(session.getProperties().getProperty("mail.smtp.ssl.checkserveridentity")).isNull();
		assertThat(session.getProperties().getProperty("mail.smtps.ssl.checkserveridentity")).isNull();
	}

	@Test
	public void testSignWithSmime_WithConfigObject() {
		final EmailGovernanceImpl emailGovernance = new EmailGovernanceImpl(null, loadPkcs12KeyStore());
		final Mailer mailer = new MailerImpl(null, SMTP, emailGovernance, createEmptyProxyConfig(), session, createDummyOperationalConfig(EMPTY_LIST, true, false));

		assertThat(mailer.getEmailGovernance().getPkcs12ConfigForSmimeSigning()).isNotNull();
		assertThat(mailer.getEmailGovernance().getPkcs12ConfigForSmimeSigning().getPkcs12StoreData()).isNotNull();
		assertThat(mailer.getEmailGovernance().getPkcs12ConfigForSmimeSigning().getStorePassword()).isEqualTo("letmein".toCharArray());
		assertThat(mailer.getEmailGovernance().getPkcs12ConfigForSmimeSigning().getKeyAlias()).isEqualTo("smime_test_user_alias");
		assertThat(mailer.getEmailGovernance().getPkcs12ConfigForSmimeSigning().getKeyPassword()).isEqualTo("letmein".toCharArray());
	}

	@NotNull
	private List<String> asList(String... args) {
		return Arrays.asList(args);
	}

	@NotNull
	private ProxyConfig createEmptyProxyConfig() {
		return new ProxyConfigImpl(null, null, null, null, null);
	}

	@NotNull
	private EmailGovernance createDummyEmailGovernance() {
		return new EmailGovernanceImpl(null, null);
	}
}