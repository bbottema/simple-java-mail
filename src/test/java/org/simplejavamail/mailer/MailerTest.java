package org.simplejavamail.mailer;

import net.markenwerk.utils.mail.dkim.DkimMessage;
import org.junit.Before;
import org.junit.Test;
import org.simplejavamail.converter.EmailConverter;
import org.simplejavamail.email.Email;
import org.simplejavamail.email.EmailPopulatingBuilder;
import org.simplejavamail.mailer.MailerBuilder.MailerRegularBuilder;
import org.simplejavamail.mailer.config.TransportStrategy;
import org.simplejavamail.util.ConfigLoader;
import testutil.ConfigLoaderTestHelper;
import testutil.EmailHelper;

import javax.mail.MessagingException;
import javax.mail.Session;
import javax.mail.internet.MimeMessage;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.util.Properties;

import static javax.xml.bind.DatatypeConverter.parseBase64Binary;
import static org.assertj.core.api.Assertions.assertThat;
import static org.simplejavamail.mailer.config.TransportStrategy.SMTPS;
import static org.simplejavamail.mailer.config.TransportStrategy.SMTP_TLS;
import static org.simplejavamail.util.ConfigLoader.Property.OPPORTUNISTIC_TLS;

@SuppressWarnings("unused")
public class MailerTest {

	private static final String MAIL_SMTP_HOST = "mail.smtp.host";
	private static final String MAIL_SMTP_STARTTLS_ENABLE = "mail.smtp.starttls.enable";
	private static final String MAIL_SMTP_STARTTLS_REQUIRED = "mail.smtp.starttls.required";
	private static final String MAIL_SMTP_SSL_TRUST = "mail.smtp.ssl.trust";
	private static final String MAIL_SMTP_SSL_CHECKSERVERIDENTITY = "mail.smtp.ssl.checkserveridentity";
	private static final String MAIL_SMTP_PORT = "mail.smtp.port";
	private static final String MAIL_TRANSPORT_PROTOCOL = "mail.transport.protocol";
	private static final String MAIL_SMTP_USERNAME = "mail.smtp.username";
	private static final String MAIL_SMTP_AUTH = "mail.smtp.auth";
	private static final String MAIL_SMTP_SOCKS_HOST = "mail.smtp.socks.host";
	private static final String MAIL_SMTP_SOCKS_PORT = "mail.smtp.socks.port";
	private static final String EXTRA_1 = "extra1";
	private static final String EXTRA_2 = "extra2";
	private static final String VALUE_1 = "value1";
	private static final String VALUE_2 = "value2";
	private static final String PORT_25 = "25";
	private static final String STR_1081 = "1081";
	private static final String SMTP = "smtp";
	private static final String USERNAME_SMTP = "username smtp";
	private static final String SMTP_HOST = "smtp host";
	private static final String PROXY_HOST = "proxy host";
	private static final String HOST = "host";
	private static final String LOCALHOST = "localhost";
	private static final String SMTP_DEFAULT_COM = "smtp.default.com";
	private static final String OVERRIDDEN_ = "overridden ";
	private static final String OVERRIDDEN_SMTP_HOST = "overridden smtp host";
	private static final String FALSE = "false";
	private static final String TRUE = "true";

	@Before
	public void restoreOriginalStaticProperties() {
		final String s = "simplejavamail.javaxmail.debug=true\n"
				+ "simplejavamail.transportstrategy=SMTP_TLS\n"
				+ "simplejavamail.smtp.host=smtp.default.com\n"
				+ "simplejavamail.smtp.port=25\n"
				+ "simplejavamail.smtp.username=username smtp\n"
				+ "simplejavamail.smtp.password=password smtp\n"
				+ "simplejavamail.proxy.host=proxy.default.com\n"
				+ "simplejavamail.proxy.port=1080\n"
				+ "simplejavamail.proxy.username=username proxy\n"
				+ "simplejavamail.proxy.password=password proxy\n"
				+ "simplejavamail.proxy.socks5bridge.port=1081";
		ConfigLoader.loadProperties(new ByteArrayInputStream(s.getBytes()), false);
	}
	
	@Test
	public void createMailSession_MinimalConstructor_WithoutConfig() {
		ConfigLoaderTestHelper.clearConfigProperties();
		
		final Mailer mailer = MailerBuilder.withSMTPServer(HOST, 25, null, null).buildMailer();
		final Session session = mailer.getSession();
		
		assertThat(session.getDebug()).isFalse();

		assertThat(session.getProperty(MAIL_SMTP_HOST)).isEqualTo(HOST);
		assertThat(session.getProperty(MAIL_SMTP_PORT)).isEqualTo(PORT_25);
		assertThat(session.getProperty(MAIL_TRANSPORT_PROTOCOL)).isEqualTo(SMTP);
		assertThat(session.getProperty(MAIL_SMTP_STARTTLS_ENABLE)).isEqualTo(TRUE);
		assertThat(session.getProperty(MAIL_SMTP_STARTTLS_REQUIRED)).isEqualTo(FALSE);
		assertThat(session.getProperty(MAIL_SMTP_SSL_TRUST)).isEqualTo("*");
		assertThat(session.getProperty(MAIL_SMTP_SSL_CHECKSERVERIDENTITY)).isEqualTo(FALSE);
		
		assertThat(session.getProperty(MAIL_SMTP_USERNAME)).isNull();
		assertThat(session.getProperty(MAIL_SMTP_AUTH)).isNull();
		assertThat(session.getProperty(MAIL_SMTP_SOCKS_HOST)).isNull();
		assertThat(session.getProperty(MAIL_SMTP_SOCKS_PORT)).isNull();
		
		// all constructors, providing the same minimal information
		final Mailer alternative1 = MailerBuilder.withSMTPServer(HOST, 25).buildMailer();
		final Mailer alternative2 = MailerBuilder.usingSession(session).buildMailer();
		
		assertThat(session.getProperties()).isEqualTo(alternative1.getSession().getProperties());
		assertThat(session.getProperties()).isEqualTo(alternative2.getSession().getProperties());
	}
	
	@Test
	public void createMailSession_AnonymousProxyConstructor_WithoutConfig() {
		ConfigLoaderTestHelper.clearConfigProperties();
		
		final Mailer mailer = createFullyConfiguredMailer(false, "", SMTP_TLS);
		
		final Session session = mailer.getSession();
		
		assertThat(session.getDebug()).isTrue();
		assertThat(session.getProperty(MAIL_SMTP_HOST)).isEqualTo(SMTP_HOST);
		assertThat(session.getProperty(MAIL_SMTP_PORT)).isEqualTo(PORT_25);
		assertThat(session.getProperty(MAIL_TRANSPORT_PROTOCOL)).isEqualTo(SMTP);
		
		assertThat(session.getProperty(MAIL_SMTP_STARTTLS_ENABLE)).isEqualTo(TRUE);
		assertThat(session.getProperty(MAIL_SMTP_STARTTLS_REQUIRED)).isEqualTo(TRUE);
		assertThat(session.getProperty(MAIL_SMTP_SSL_CHECKSERVERIDENTITY)).isEqualTo(TRUE);
		
		assertThat(session.getProperty(MAIL_SMTP_USERNAME)).isEqualTo(USERNAME_SMTP);
		assertThat(session.getProperty(MAIL_SMTP_AUTH)).isEqualTo(TRUE);
		assertThat(session.getProperty(MAIL_SMTP_SOCKS_HOST)).isEqualTo(PROXY_HOST);
		assertThat(session.getProperty(MAIL_SMTP_SOCKS_PORT)).isEqualTo("1080");
		assertThat(session.getProperty(EXTRA_1)).isEqualTo(VALUE_1);
		assertThat(session.getProperty(EXTRA_2)).isEqualTo(VALUE_2);
	}
	
	@Test
	public void createMailSession_MaximumConstructor_WithoutConfig() {
		ConfigLoaderTestHelper.clearConfigProperties();
		
		final Mailer mailer = createFullyConfiguredMailer(true, "", SMTP_TLS);
		
		final Session session = mailer.getSession();
		
		assertThat(session.getDebug()).isTrue();
		assertThat(session.getProperty(MAIL_SMTP_HOST)).isEqualTo(SMTP_HOST);
		assertThat(session.getProperty(MAIL_SMTP_PORT)).isEqualTo(PORT_25);
		assertThat(session.getProperty(MAIL_TRANSPORT_PROTOCOL)).isEqualTo(SMTP);
		assertThat(session.getProperty(MAIL_SMTP_STARTTLS_ENABLE)).isEqualTo(TRUE);
		assertThat(session.getProperty(MAIL_SMTP_STARTTLS_REQUIRED)).isEqualTo(TRUE);
		assertThat(session.getProperty(MAIL_SMTP_SSL_CHECKSERVERIDENTITY)).isEqualTo(TRUE);
		assertThat(session.getProperty(MAIL_SMTP_USERNAME)).isEqualTo(USERNAME_SMTP);
		assertThat(session.getProperty(MAIL_SMTP_AUTH)).isEqualTo(TRUE);
		// the following two are because authentication is needed, otherwise proxy would be straightworward
		assertThat(session.getProperty(MAIL_SMTP_SOCKS_HOST)).isEqualTo(LOCALHOST);
		assertThat(session.getProperty(MAIL_SMTP_SOCKS_PORT)).isEqualTo("999");
		assertThat(session.getProperty(EXTRA_1)).isEqualTo(VALUE_1);
		assertThat(session.getProperty(EXTRA_2)).isEqualTo(VALUE_2);
	}
	
	@Test
	public void createMailSession_MinimalConstructor_WithConfig() {
		final Mailer mailer = MailerBuilder.buildMailer();
		final Session session = mailer.getSession();
		
		assertThat(session.getDebug()).isTrue();

		assertThat(session.getProperty(MAIL_SMTP_HOST)).isEqualTo(SMTP_DEFAULT_COM);
		assertThat(session.getProperty(MAIL_SMTP_PORT)).isEqualTo(PORT_25);
		assertThat(session.getProperty(MAIL_TRANSPORT_PROTOCOL)).isEqualTo(SMTP);
		assertThat(session.getProperty(MAIL_SMTP_STARTTLS_ENABLE)).isEqualTo(TRUE);
		assertThat(session.getProperty(MAIL_SMTP_STARTTLS_REQUIRED)).isEqualTo(TRUE);
		assertThat(session.getProperty(MAIL_SMTP_SSL_CHECKSERVERIDENTITY)).isEqualTo(TRUE);
		assertThat(session.getProperty(MAIL_SMTP_USERNAME)).isEqualTo(USERNAME_SMTP);
		assertThat(session.getProperty(MAIL_SMTP_AUTH)).isEqualTo(TRUE);
		// the following two are because authentication is needed, otherwise proxy would be straightworward
		assertThat(session.getProperty(MAIL_SMTP_SOCKS_HOST)).isEqualTo(LOCALHOST);
		assertThat(session.getProperty(MAIL_SMTP_SOCKS_PORT)).isEqualTo(STR_1081);
	}
	
	@Test
	public void createMailSession_MinimalConstructor_WithConfig_OPPORTUNISTIC_TLS() {
		final Properties properties = new Properties();
		properties.setProperty(OPPORTUNISTIC_TLS.key(), FALSE);
		ConfigLoader.loadProperties(properties, true);
		
		final Mailer mailer = MailerBuilder.withTransportStrategy(TransportStrategy.SMTP).buildMailer();
		final Session session = mailer.getSession();
		
		assertThat(session.getDebug()).isTrue();
		assertThat(session.getProperty(MAIL_SMTP_HOST)).isEqualTo(SMTP_DEFAULT_COM);
		assertThat(session.getProperty(MAIL_SMTP_PORT)).isEqualTo(PORT_25);
		assertThat(session.getProperty(MAIL_TRANSPORT_PROTOCOL)).isEqualTo(SMTP);
		
		assertThat(session.getProperty(MAIL_SMTP_STARTTLS_ENABLE)).isNull();
		assertThat(session.getProperty(MAIL_SMTP_STARTTLS_REQUIRED)).isNull();
		assertThat(session.getProperty(MAIL_SMTP_SSL_CHECKSERVERIDENTITY)).isNull();
		
		assertThat(session.getProperty(MAIL_SMTP_USERNAME)).isEqualTo(USERNAME_SMTP);
		assertThat(session.getProperty(MAIL_SMTP_AUTH)).isEqualTo(TRUE);
		// the following two are because authentication is needed, otherwise proxy would be straightworward
		assertThat(session.getProperty(MAIL_SMTP_SOCKS_HOST)).isEqualTo(LOCALHOST);
		assertThat(session.getProperty(MAIL_SMTP_SOCKS_PORT)).isEqualTo(STR_1081);
	}
	
	@Test
	public void createMailSession_MinimalConstructor_WithConfig_OPPORTUNISTIC_TLS_Manually_Disabled() {
		final Properties properties = new Properties();
		properties.setProperty(OPPORTUNISTIC_TLS.key(), FALSE);
		ConfigLoader.loadProperties(properties, true);
		
		TransportStrategy.SMTP.setOpportunisticTLS(true);
		
		final Mailer mailer = MailerBuilder.withTransportStrategy(TransportStrategy.SMTP).buildMailer();
		final Session session = mailer.getSession();
		
		assertThat(session.getDebug()).isTrue();
		assertThat(session.getProperty(MAIL_SMTP_HOST)).isEqualTo(SMTP_DEFAULT_COM);
		assertThat(session.getProperty(MAIL_SMTP_PORT)).isEqualTo(PORT_25);
		assertThat(session.getProperty(MAIL_TRANSPORT_PROTOCOL)).isEqualTo(SMTP);
		assertThat(session.getProperty(MAIL_SMTP_STARTTLS_ENABLE)).isEqualTo(TRUE);
		assertThat(session.getProperty(MAIL_SMTP_STARTTLS_REQUIRED)).isEqualTo(FALSE);
		assertThat(session.getProperty(MAIL_SMTP_SSL_TRUST)).isEqualTo("*");
		assertThat(session.getProperty(MAIL_SMTP_SSL_CHECKSERVERIDENTITY)).isEqualTo(FALSE);
		
		assertThat(session.getProperty(MAIL_SMTP_USERNAME)).isEqualTo(USERNAME_SMTP);
		assertThat(session.getProperty(MAIL_SMTP_AUTH)).isEqualTo(TRUE);
		// the following two are because authentication is needed, otherwise proxy would be straightworward
		assertThat(session.getProperty(MAIL_SMTP_SOCKS_HOST)).isEqualTo(LOCALHOST);
		assertThat(session.getProperty(MAIL_SMTP_SOCKS_PORT)).isEqualTo(STR_1081);
	}
	
	@Test
	public void createMailSession_MaximumConstructor_WithConfig() {
		final Mailer mailer = createFullyConfiguredMailer(false, OVERRIDDEN_, SMTP_TLS);
		
		final Session session = mailer.getSession();
		
		assertThat(session.getDebug()).isTrue();
		assertThat(session.getProperty(MAIL_SMTP_HOST)).isEqualTo(OVERRIDDEN_SMTP_HOST);
		assertThat(session.getProperty(MAIL_SMTP_PORT)).isEqualTo(PORT_25);
		assertThat(session.getProperty(MAIL_TRANSPORT_PROTOCOL)).isEqualTo(SMTP);
		assertThat(session.getProperty(MAIL_SMTP_STARTTLS_ENABLE)).isEqualTo(TRUE);
		assertThat(session.getProperty(MAIL_SMTP_STARTTLS_REQUIRED)).isEqualTo(TRUE);
		assertThat(session.getProperty(MAIL_SMTP_SSL_CHECKSERVERIDENTITY)).isEqualTo(TRUE);
		assertThat(session.getProperty(MAIL_SMTP_USERNAME)).isEqualTo("overridden username smtp");
		assertThat(session.getProperty(MAIL_SMTP_AUTH)).isEqualTo(TRUE);
		// the following two are because authentication is needed, otherwise proxy would be straightworward
		assertThat(session.getProperty(MAIL_SMTP_SOCKS_HOST)).isEqualTo(LOCALHOST);
		assertThat(session.getProperty(MAIL_SMTP_SOCKS_PORT)).isEqualTo(STR_1081);
		assertThat(session.getProperty(EXTRA_1)).isEqualTo("overridden value1");
		assertThat(session.getProperty(EXTRA_2)).isEqualTo("overridden value2");
	}
	
	@Test
	public void createMailSession_MaximumConstructor_WithConfig_TLS() {
		final Mailer mailer = createFullyConfiguredMailer(false, OVERRIDDEN_, SMTPS);
		
		final Session session = mailer.getSession();
		
		assertThat(session.getDebug()).isTrue();
		assertThat(session.getProperty("mail.smtps.host")).isEqualTo(OVERRIDDEN_SMTP_HOST);
		assertThat(session.getProperty("mail.smtps.port")).isEqualTo(PORT_25);
		assertThat(session.getProperty(MAIL_TRANSPORT_PROTOCOL)).isEqualTo("smtps");
		assertThat(session.getProperty("mail.smtps.quitwait")).isEqualTo(FALSE);
		assertThat(session.getProperty("mail.smtps.username")).isEqualTo("overridden username smtp");
		assertThat(session.getProperty("mail.smtps.auth")).isEqualTo(TRUE);
		assertThat(session.getProperty(EXTRA_1)).isEqualTo("overridden value1");
		assertThat(session.getProperty(EXTRA_2)).isEqualTo("overridden value2");
	}
	
	@Test
	public void testDKIMPriming()
			throws IOException {
		final EmailPopulatingBuilder emailPopulatingBuilder = EmailHelper.createDummyEmailBuilder(true, false, false);
		
		// System.out.println(printBase64Binary(Files.readAllBytes(Paths.get("D:\\keys\\dkim.der")))); // needs jdk 1.7
		final String privateDERkeyBase64 =
				"MIICdgIBADANBgkqhkiG9w0BAQEFAASCAmAwggJcAgEAAoGBAMYuC7ZjFBSWJtP6JH8w1deJE+5sLwkUacZcW4MTVQXTM33BzN8Ec64KO1Hk2B9oxkpdunKt"
						+ "BggwbWMlGU5gGu4PpQ20cdPcfBIkUMlQKaakHPPGNYaF9dQaZIRy8XON6g1sOJGALXtUYX1r5hdDH13kC/YBw9f1Dsi2smrB0qabAgMBAAECgYAdWbBuYJoWum4hssg49hiVhT2ob+k"
						+ "/ZQCNWhxLe096P18+3rbiyJwBSI6kgEnpzPChDuSQG0PrbpCkwFfRHbafDIPiMi5b6YZkJoFmmOmBHsewS1VdR/phk+aPQV2SoJ0S0FAGZkOnOkagHfmEMSgjZzTpJouu5NU8mwqz8z"
						+ "/s0QJBAOUnELTMG/Se3Pw4FQ49K49lA81QaMoL63lYIEvc6uSVoJSEcrBFxv5sfJW2LFWs8VIDyTvYzsCjLwZj6nwA3k0CQQDdZgVHX7crlpUxO/cjKtTa/Nq9S6XLv3S6XX3YJJ9/Z"
						+ "pYpqAWJbbR+8scBgVxS+9NLLeHhlx/EvkaZRdLhwRyHAkEAtr1ThkqrFIXHxt9Wczd20HCG+qlgF5gv3WHYx4bSTx2/pBCHgWjzyxtqst1HN7+l5nicdrxsDJVVv+vYJ7FtlQJAWPgG"
						+ "Zwgvs3Rvv7k5NwifQOEbhbZAigAGCF5Jk/Ijpi6zaUn7754GSn2FOzWgxDguUKe/fcgdHBLai/1jIRVZQQJAXF2xzWMwP+TmX44QxK52QHVI8mhNzcnH7A311gWns6AbLcuLA9quwjU"
						+ "YJMRlfXk67lJXCleZL15EpVPrQ34KlA==";
		
		emailPopulatingBuilder.signWithDomainKey(new ByteArrayInputStream(parseBase64Binary(privateDERkeyBase64)), "somemail.com", "select");
		final MimeMessage mimeMessage = EmailConverter.emailToMimeMessage(emailPopulatingBuilder.buildEmail());
		// success, signing did not produce an error
		assertThat(mimeMessage).isInstanceOf(DkimMessage.class);
	}
	
	@Test
	public void testParser()
			throws Exception {
		final EmailPopulatingBuilder emailPopulatingBuilderNormal = EmailHelper.createDummyEmailBuilder(true, false, false);
		
		// let's try producing and then consuming a MimeMessage ->
		// (bounce recipient is not part of the Mimemessage, but the Envelope and is configured on the Session, so just ignore this)
		emailPopulatingBuilderNormal.clearBounceTo();
		final Email emailNormal = emailPopulatingBuilderNormal.buildEmail();
		final MimeMessage mimeMessage = EmailConverter.emailToMimeMessage(emailNormal);
		final Email emailFromMimeMessage = EmailConverter.mimeMessageToEmail(mimeMessage);
		
		
		assertThat(emailFromMimeMessage).isEqualTo(emailNormal);
	}
	
	private Mailer createFullyConfiguredMailer(final boolean authenticateProxy, final String prefix, final TransportStrategy transportStrategy) {
		final MailerRegularBuilder mailerBuilder = MailerBuilder
				.withSMTPServer(prefix + SMTP_HOST, 25, prefix + USERNAME_SMTP, prefix + "password smtp")
				.withTransportStrategy(transportStrategy)
				.withDebugLogging(true);
		
		if (transportStrategy == SMTP_TLS) {
			if (authenticateProxy) {
				mailerBuilder
						.withProxy(prefix + PROXY_HOST, 1080, prefix + "username proxy", prefix + "password proxy")
						.withProxyBridgePort(999);
			} else {
				mailerBuilder.withProxy(prefix + PROXY_HOST, 1080);
			}
		} else if (transportStrategy == SMTPS) {
			mailerBuilder.clearProxy();
		}
		
		return mailerBuilder
				.withProperty(EXTRA_1, prefix + VALUE_1)
				.withProperty(EXTRA_2, prefix + VALUE_2)
				.buildMailer();
	}
}