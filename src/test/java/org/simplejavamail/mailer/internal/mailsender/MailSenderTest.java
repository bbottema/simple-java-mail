package org.simplejavamail.mailer.internal.mailsender;

import org.assertj.core.api.ThrowableAssert;
import org.junit.Before;
import org.junit.Test;
import org.simplejavamail.mailer.config.ProxyConfig;

import javax.mail.Session;
import java.util.Properties;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.simplejavamail.mailer.config.TransportStrategy.SMTP;
import static org.simplejavamail.mailer.config.TransportStrategy.SMTPS;

public class MailSenderTest {
	
	private Session session;
	
	@Before
	public void setup() {
		session = Session.getDefaultInstance(new Properties());
	}
	
	@Test
	public void setDebug() {
		MailSender mailSender = new MailSender(session, null, null);
		mailSender.setDebug(true);
		assertThat(session.getDebug()).isTrue();
		mailSender.setDebug(false);
		assertThat(session.getDebug()).isFalse();
	}
	
	@Test
	public void trustHosts_WithoutTransportStrategy() {
		assertThatThrownBy(new ThrowableAssert.ThrowingCallable() {
			@Override
			public void call() throws Throwable {
				new MailSender(session, null, null).trustHosts();
			}
		})
				.isInstanceOf(MailSenderException.class)
				.hasMessage("Cannot determine the trust properties to set without a provided transport strategy");
		
	}
	
	@Test
	public void trustAllHosts_PLAIN() {
		MailSender mailSender = new MailSender(session, null, SMTP);
		mailSender.trustAllHosts(true);
		assertThat(session.getProperties().getProperty("mail.smtp.ssl.trust")).isEqualTo("*");
		mailSender.trustAllHosts(false);
		assertThat(session.getProperties().getProperty("mail.smtp.ssl.trust")).isNull();
	}
	
	@Test
	public void trustAllHosts_SMTPS() {
		ProxyConfig proxyBypassingMock = mock(ProxyConfig.class);
		when(proxyBypassingMock.requiresProxy()).thenReturn(false);
		MailSender mailSender = new MailSender(session, proxyBypassingMock, SMTPS);
		mailSender.trustAllHosts(true);
		assertThat(session.getProperties().getProperty("mail.smtps.ssl.trust")).isEqualTo("*");
		mailSender.trustAllHosts(false);
		assertThat(session.getProperties().getProperty("mail.smtps.ssl.trust")).isNull();
	}
	
	@Test
	public void trustHosts() {
		MailSender mailSender = new MailSender(session, null, SMTP);
		mailSender.trustHosts();
		assertThat(session.getProperties().getProperty("mail.smtp.ssl.trust")).isNull();
		mailSender.trustHosts("a");
		assertThat(session.getProperties().getProperty("mail.smtp.ssl.trust")).isEqualTo("a");
		mailSender.trustHosts("a", "b");
		assertThat(session.getProperties().getProperty("mail.smtp.ssl.trust")).isEqualTo("a b");
		mailSender.trustHosts("a", "b", "c");
		assertThat(session.getProperties().getProperty("mail.smtp.ssl.trust")).isEqualTo("a b c");
	}
}