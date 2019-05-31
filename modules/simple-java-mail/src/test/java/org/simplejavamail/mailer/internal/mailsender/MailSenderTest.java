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
import java.util.concurrent.Executors;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.simplejavamail.api.mailer.config.TransportStrategy.SMTP;
import static org.simplejavamail.api.mailer.config.TransportStrategy.SMTPS;

public class MailSenderTest {
	
	private Session session;
	
	private static final List<String> EMPTY_LIST = Collections.emptyList();
	
	@Before
	public void setup() {
		session = Session.getDefaultInstance(new Properties());
	}
	
	@Nonnull
	@SuppressWarnings("deprecation")
	private ProxyConfig createEmptyProxyConfig() {
		return new ProxyConfig(null, null, null, null, null);
	}
	
	@Test
	public void trustAllHosts_PLAIN() {
		new MailSenderImpl(session, createDummyOperationalConfig(EMPTY_LIST, true), createEmptyProxyConfig(), SMTP);
		assertThat(session.getProperties().getProperty("mail.smtp.ssl.trust")).isEqualTo("*");
		new MailSenderImpl(session, createDummyOperationalConfig(EMPTY_LIST, false), createEmptyProxyConfig(), SMTP);
		assertThat(session.getProperties().getProperty("mail.smtp.ssl.trust")).isNull();
	}
	
	@Test
	public void trustAllHosts_SMTPS() {
		ProxyConfig proxyBypassingMock = mock(ProxyConfig.class);
		when(proxyBypassingMock.requiresProxy()).thenReturn(false);
		new MailSenderImpl(session, createDummyOperationalConfig(EMPTY_LIST, true), proxyBypassingMock, SMTPS);
		assertThat(session.getProperties().getProperty("mail.smtps.ssl.trust")).isEqualTo("*");
		new MailSenderImpl(session, createDummyOperationalConfig(EMPTY_LIST, false), proxyBypassingMock, SMTPS);
		assertThat(session.getProperties().getProperty("mail.smtps.ssl.trust")).isNull();
	}
	
	@Test
	public void trustHosts() {
		new MailSenderImpl(session, createDummyOperationalConfig(asList(), false), createEmptyProxyConfig(), SMTP);
		assertThat(session.getProperties().getProperty("mail.smtp.ssl.trust")).isNull();
		new MailSenderImpl(session, createDummyOperationalConfig(asList("a"), false), createEmptyProxyConfig(), SMTP);
		assertThat(session.getProperties().getProperty("mail.smtp.ssl.trust")).isEqualTo("a");
		new MailSenderImpl(session, createDummyOperationalConfig(asList("a", "b"), false), createEmptyProxyConfig(), SMTP);
		assertThat(session.getProperties().getProperty("mail.smtp.ssl.trust")).isEqualTo("a b");
		new MailSenderImpl(session, createDummyOperationalConfig(asList("a", "b", "c"), false), createEmptyProxyConfig(), SMTP);
		assertThat(session.getProperties().getProperty("mail.smtp.ssl.trust")).isEqualTo("a b c");
	}
	
	@Nonnull
	private List<String> asList(String... args) {
		return Arrays.asList(args);
	}
	
	@Nonnull
	@SuppressWarnings("deprecation")
	private OperationalConfig createDummyOperationalConfig(List<String> hostsToTrust, boolean trustAllSSLHost) {
		return new OperationalConfigImpl(false, new Properties(), 0, 10, 1000, false, false, hostsToTrust, trustAllSSLHost,
				Executors.newSingleThreadExecutor());
	}
}