package org.simplejavamail.mailer.internal;

import org.hazlewood.connor.bottema.emailaddress.EmailAddressCriteria;
import org.junit.Before;
import org.junit.Test;
import org.simplejavamail.api.mailer.config.OperationalConfig;
import org.simplejavamail.api.mailer.config.ProxyConfig;

import javax.annotation.Nonnull;
import javax.mail.Session;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Properties;
import java.util.concurrent.Executors;

import static java.util.EnumSet.noneOf;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.simplejavamail.api.mailer.config.TransportStrategy.SMTP;
import static org.simplejavamail.api.mailer.config.TransportStrategy.SMTPS;

@SuppressWarnings("deprecation")
public class MailerImplTest {
	
	private Session session;
	
	private static final List<String> EMPTY_LIST = Collections.emptyList();
	
	@Before
	public void setup() {
		session = Session.getDefaultInstance(new Properties());
	}
	
	@Nonnull
	private ProxyConfig createEmptyProxyConfig() {
		return new ProxyConfigImpl(null, null, null, null, null);
	}
	
	@Test
	public void trustAllHosts_PLAIN() {
		new MailerImpl(null, SMTP, noneOf(EmailAddressCriteria.class), createEmptyProxyConfig(), session, createDummyOperationalConfig(EMPTY_LIST, true));
		assertThat(session.getProperties().getProperty("mail.smtp.ssl.trust")).isEqualTo("*");
		new MailerImpl(null, SMTP, noneOf(EmailAddressCriteria.class), createEmptyProxyConfig(), session, createDummyOperationalConfig(EMPTY_LIST, false));
		assertThat(session.getProperties().getProperty("mail.smtp.ssl.trust")).isNull();
	}
	
	@Test
	public void trustAllHosts_SMTPS() {
		ProxyConfig proxyBypassingMock = mock(ProxyConfig.class);
		when(proxyBypassingMock.requiresProxy()).thenReturn(false);
		new MailerImpl(null, SMTPS, noneOf(EmailAddressCriteria.class), proxyBypassingMock, session, createDummyOperationalConfig(EMPTY_LIST, true));
		assertThat(session.getProperties().getProperty("mail.smtps.ssl.trust")).isEqualTo("*");
		new MailerImpl(null, SMTPS, noneOf(EmailAddressCriteria.class), proxyBypassingMock, session, createDummyOperationalConfig(EMPTY_LIST, false));
		assertThat(session.getProperties().getProperty("mail.smtps.ssl.trust")).isNull();
	}
	
	@Test
	public void trustHosts() {
		new MailerImpl(null, SMTP, noneOf(EmailAddressCriteria.class), createEmptyProxyConfig(), session, createDummyOperationalConfig(asList(), false));
		assertThat(session.getProperties().getProperty("mail.smtp.ssl.trust")).isNull();
		new MailerImpl(null, SMTP, noneOf(EmailAddressCriteria.class), createEmptyProxyConfig(), session, createDummyOperationalConfig(asList("a"), false));
		assertThat(session.getProperties().getProperty("mail.smtp.ssl.trust")).isEqualTo("a");
		new MailerImpl(null, SMTP, noneOf(EmailAddressCriteria.class), createEmptyProxyConfig(), session, createDummyOperationalConfig(asList("a", "b"), false));
		assertThat(session.getProperties().getProperty("mail.smtp.ssl.trust")).isEqualTo("a b");
		new MailerImpl(null, SMTP, noneOf(EmailAddressCriteria.class), createEmptyProxyConfig(), session, createDummyOperationalConfig(asList("a", "b", "c"), false));
		assertThat(session.getProperties().getProperty("mail.smtp.ssl.trust")).isEqualTo("a b c");
	}
	
	@Nonnull
	private List<String> asList(String... args) {
		return Arrays.asList(args);
	}
	
	@Nonnull
	private OperationalConfig createDummyOperationalConfig(List<String> hostsToTrust, boolean trustAllSSLHost) {
		return new OperationalConfigImpl(false, new Properties(), 0, 10, 1000, false, false, hostsToTrust, trustAllSSLHost,
				Executors.newSingleThreadExecutor());
	}
}