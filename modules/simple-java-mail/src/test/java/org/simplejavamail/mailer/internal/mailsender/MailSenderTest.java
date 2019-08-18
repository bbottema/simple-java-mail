package org.simplejavamail.mailer.internal.mailsender;

import org.junit.Before;
import org.junit.Test;
import org.simplejavamail.api.mailer.config.OperationalConfig;
import org.simplejavamail.api.mailer.config.ProxyConfig;
import org.simplejavamail.mailer.internal.OperationalConfigImpl;

import javax.annotation.Nonnull;
import javax.mail.Session;
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

public class MailSenderTest {
	
	private Session session;
	
	private static final List<String> EMPTY_LIST = Collections.emptyList();
	
	@Before
	public void setup() {
		session = Session.getInstance(new Properties());
	}
	
	@Nonnull
	@SuppressWarnings("deprecation")
	private ProxyConfig createEmptyProxyConfig() {
		return new ProxyConfig(null, null, null, null, null);
	}
	
	@Test
	public void trustAllHosts_PLAIN() {
		new MailSenderImpl(session, createDummyOperationalConfig(EMPTY_LIST, true, false), createEmptyProxyConfig(), SMTP);
		assertThat(session.getProperties().getProperty("mail.smtp.ssl.trust")).isEqualTo("*");
		assertThat(session.getProperties().getProperty("mail.smtp.ssl.checkserveridentity")).isNull();
		assertThat(session.getProperties().getProperty("mail.smtps.ssl.checkserveridentity")).isNull();
		new MailSenderImpl(session, createDummyOperationalConfig(EMPTY_LIST, false, true), createEmptyProxyConfig(), SMTP);
		assertThat(session.getProperties().getProperty("mail.smtp.ssl.trust")).isNull();
		assertThat(session.getProperties().getProperty("mail.smtp.ssl.checkserveridentity")).isNull();
		assertThat(session.getProperties().getProperty("mail.smtps.ssl.checkserveridentity")).isNull();
	}
	
	@Test
	public void trustAllHosts_SMTPS() {
		ProxyConfig proxyBypassingMock = mock(ProxyConfig.class);
		when(proxyBypassingMock.requiresProxy()).thenReturn(false);
		new MailSenderImpl(session, createDummyOperationalConfig(EMPTY_LIST, true, false), proxyBypassingMock, SMTPS);
		assertThat(session.getProperties().getProperty("mail.smtps.ssl.trust")).isEqualTo("*");
		assertThat(session.getProperties().getProperty("mail.smtp.ssl.checkserveridentity")).isNull();
		assertThat(session.getProperties().getProperty("mail.smtps.ssl.checkserveridentity")).isEqualTo("false");
		new MailSenderImpl(session, createDummyOperationalConfig(EMPTY_LIST, false, true), proxyBypassingMock, SMTPS);
		assertThat(session.getProperties().getProperty("mail.smtps.ssl.trust")).isNull();
		assertThat(session.getProperties().getProperty("mail.smtp.ssl.checkserveridentity")).isNull();
		assertThat(session.getProperties().getProperty("mail.smtps.ssl.checkserveridentity")).isEqualTo("true");
	}

	@Test
	public void trustAllHosts_SMTP_TLS() {
		ProxyConfig proxyBypassingMock = mock(ProxyConfig.class);
		when(proxyBypassingMock.requiresProxy()).thenReturn(false);
		new MailSenderImpl(session, createDummyOperationalConfig(EMPTY_LIST, true, false), proxyBypassingMock, SMTP_TLS);
		assertThat(session.getProperties().getProperty("mail.smtp.ssl.trust")).isEqualTo("*");
		assertThat(session.getProperties().getProperty("mail.smtp.ssl.checkserveridentity")).isEqualTo("false");
		assertThat(session.getProperties().getProperty("mail.smtps.ssl.checkserveridentity")).isNull();
		new MailSenderImpl(session, createDummyOperationalConfig(EMPTY_LIST, false, true), proxyBypassingMock, SMTP_TLS);
		assertThat(session.getProperties().getProperty("mail.smtps.ssl.trust")).isNull();
		assertThat(session.getProperties().getProperty("mail.smtp.ssl.checkserveridentity")).isEqualTo("true");
		assertThat(session.getProperties().getProperty("mail.smtps.ssl.checkserveridentity")).isNull();
	}
	
	@Test
	public void trustHosts() {
		new MailSenderImpl(session, createDummyOperationalConfig(asList(), false, false), createEmptyProxyConfig(), SMTP);
		assertThat(session.getProperties().getProperty("mail.smtp.ssl.trust")).isNull();
		assertThat(session.getProperties().getProperty("mail.smtp.ssl.checkserveridentity")).isNull();
		assertThat(session.getProperties().getProperty("mail.smtps.ssl.checkserveridentity")).isNull();
		new MailSenderImpl(session, createDummyOperationalConfig(asList("a"), false, false), createEmptyProxyConfig(), SMTP);
		assertThat(session.getProperties().getProperty("mail.smtp.ssl.trust")).isEqualTo("a");
		assertThat(session.getProperties().getProperty("mail.smtp.ssl.checkserveridentity")).isNull();
		assertThat(session.getProperties().getProperty("mail.smtps.ssl.checkserveridentity")).isNull();
		new MailSenderImpl(session, createDummyOperationalConfig(asList("a", "b"), false, false), createEmptyProxyConfig(), SMTP);
		assertThat(session.getProperties().getProperty("mail.smtp.ssl.trust")).isEqualTo("a b");
		assertThat(session.getProperties().getProperty("mail.smtp.ssl.checkserveridentity")).isNull();
		assertThat(session.getProperties().getProperty("mail.smtps.ssl.checkserveridentity")).isNull();
		new MailSenderImpl(session, createDummyOperationalConfig(asList("a", "b", "c"), false, true), createEmptyProxyConfig(), SMTP);
		assertThat(session.getProperties().getProperty("mail.smtp.ssl.trust")).isEqualTo("a b c");
		assertThat(session.getProperties().getProperty("mail.smtp.ssl.checkserveridentity")).isNull();
		assertThat(session.getProperties().getProperty("mail.smtps.ssl.checkserveridentity")).isNull();
	}
	
	@Nonnull
	private List<String> asList(String... args) {
		return Arrays.asList(args);
	}

	@Nonnull
	@SuppressWarnings({ "SameParameterValue", "deprecation" })
	private OperationalConfig createDummyOperationalConfig(List<String> hostsToTrust, boolean trustAllSSLHost, boolean verifyServerIdentity) {
		return new OperationalConfigImpl(false, new Properties(), 0, 10, 1000, false, false, hostsToTrust, trustAllSSLHost, verifyServerIdentity, null);
	}
}