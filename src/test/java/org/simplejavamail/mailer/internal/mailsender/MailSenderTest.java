package org.simplejavamail.mailer.internal.mailsender;

import org.junit.Before;
import org.junit.Test;

import javax.mail.Session;
import java.util.Properties;

import static org.assertj.core.api.Assertions.assertThat;

public class MailSenderTest {

	private Session session;
	private MailSender mailSender;

	@Before
	public void setup() {
		session = Session.getDefaultInstance(new Properties());
		mailSender = new MailSender(session, null, null);
	}

	@Test
	public void setDebug() {
		mailSender.setDebug(true);
		assertThat(session.getDebug()).isTrue();
		mailSender.setDebug(false);
		assertThat(session.getDebug()).isFalse();
	}

	@Test
	public void trustAllHosts() {
		mailSender.trustAllHosts(true);
		assertThat(session.getProperties().getProperty("mail.smtp.ssl.trust")).isEqualTo("*");
		mailSender.trustAllHosts(false);
		assertThat(session.getProperties().getProperty("mail.smtp.ssl.trust")).isNull();
	}

	@Test
	public void trustHosts(){
		mailSender.trustHosts();
		assertThat(session.getProperties().getProperty("mail.smtp.ssl.trust")).isNull();
		mailSender.trustHosts("a");
		assertThat(session.getProperties().getProperty("mail.smtp.ssl.trust")).isEqualTo("a");
		mailSender.trustHosts("a", "b");
		assertThat(session.getProperties().getProperty("mail.smtp.ssl.trust")).isEqualTo("a,b");
		mailSender.trustHosts("a", "b", "c");
		assertThat(session.getProperties().getProperty("mail.smtp.ssl.trust")).isEqualTo("a,b,c");
	}

}