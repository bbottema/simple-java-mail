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
import org.simplejavamail.util.TestDataHelper;
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
	
	@Before
	public void restoreOriginalStaticProperties()
			throws IOException {
		String s = "simplejavamail.javaxmail.debug=true\n"
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
	public void createMailSession_MinimalConstructor_WithoutConfig()
			throws Exception {
		ConfigLoaderTestHelper.clearConfigProperties();
		
		Mailer mailer = MailerBuilder.withSMTPServer("host", 25, null, null).buildMailer();
		Session session = mailer.getSession();
		
		assertThat(session.getDebug()).isFalse();
		assertThat(session.getProperty("mail.smtp.host")).isEqualTo("host");
		assertThat(session.getProperty("mail.smtp.port")).isEqualTo("25");
		assertThat(session.getProperty("mail.transport.protocol")).isEqualTo("smtp");
		
		assertThat(session.getProperty("mail.smtp.starttls.enable")).isEqualTo("true");
		assertThat(session.getProperty("mail.smtp.starttls.required")).isEqualTo("false");
		assertThat(session.getProperty("mail.smtp.ssl.trust")).isEqualTo("*");
		assertThat(session.getProperty("mail.smtp.ssl.checkserveridentity")).isEqualTo("false");
		
		assertThat(session.getProperty("mail.smtp.username")).isNull();
		assertThat(session.getProperty("mail.smtp.auth")).isNull();
		assertThat(session.getProperty("mail.smtp.socks.host")).isNull();
		assertThat(session.getProperty("mail.smtp.socks.port")).isNull();
		
		// all constructors, providing the same minimal information
		Mailer alternative1 = MailerBuilder.withSMTPServer("host", 25).buildMailer();
		Mailer alternative2 = MailerBuilder.usingSession(session).buildMailer();
		
		assertThat(session.getProperties()).isEqualTo(alternative1.getSession().getProperties());
		assertThat(session.getProperties()).isEqualTo(alternative2.getSession().getProperties());
	}
	
	@Test
	public void createMailSession_AnonymousProxyConstructor_WithoutConfig()
			throws Exception {
		ConfigLoaderTestHelper.clearConfigProperties();
		
		Mailer mailer = createFullyConfiguredMailer(false, "", SMTP_TLS);
		
		Session session = mailer.getSession();
		
		assertThat(session.getDebug()).isTrue();
		assertThat(session.getProperty("mail.smtp.host")).isEqualTo("smtp host");
		assertThat(session.getProperty("mail.smtp.port")).isEqualTo("25");
		assertThat(session.getProperty("mail.transport.protocol")).isEqualTo("smtp");
		
		assertThat(session.getProperty("mail.smtp.starttls.enable")).isEqualTo("true");
		assertThat(session.getProperty("mail.smtp.starttls.required")).isEqualTo("true");
		assertThat(session.getProperty("mail.smtp.ssl.checkserveridentity")).isEqualTo("true");
		
		assertThat(session.getProperty("mail.smtp.username")).isEqualTo("username smtp");
		assertThat(session.getProperty("mail.smtp.auth")).isEqualTo("true");
		assertThat(session.getProperty("mail.smtp.socks.host")).isEqualTo("proxy host");
		assertThat(session.getProperty("mail.smtp.socks.port")).isEqualTo("1080");
		assertThat(session.getProperty("extra1")).isEqualTo("value1");
		assertThat(session.getProperty("extra2")).isEqualTo("value2");
	}
	
	@Test
	public void createMailSession_MaximumConstructor_WithoutConfig()
			throws Exception {
		ConfigLoaderTestHelper.clearConfigProperties();
		
		Mailer mailer = createFullyConfiguredMailer(true, "", SMTP_TLS);
		
		Session session = mailer.getSession();
		
		assertThat(session.getDebug()).isTrue();
		assertThat(session.getProperty("mail.smtp.host")).isEqualTo("smtp host");
		assertThat(session.getProperty("mail.smtp.port")).isEqualTo("25");
		assertThat(session.getProperty("mail.transport.protocol")).isEqualTo("smtp");
		assertThat(session.getProperty("mail.smtp.starttls.enable")).isEqualTo("true");
		assertThat(session.getProperty("mail.smtp.starttls.required")).isEqualTo("true");
		assertThat(session.getProperty("mail.smtp.ssl.checkserveridentity")).isEqualTo("true");
		assertThat(session.getProperty("mail.smtp.username")).isEqualTo("username smtp");
		assertThat(session.getProperty("mail.smtp.auth")).isEqualTo("true");
		// the following two are because authentication is needed, otherwise proxy would be straightworward
		assertThat(session.getProperty("mail.smtp.socks.host")).isEqualTo("localhost");
		assertThat(session.getProperty("mail.smtp.socks.port")).isEqualTo("999");
		assertThat(session.getProperty("extra1")).isEqualTo("value1");
		assertThat(session.getProperty("extra2")).isEqualTo("value2");
	}
	
	@Test
	public void createMailSession_MinimalConstructor_WithConfig() {
		Mailer mailer = MailerBuilder.buildMailer();
		Session session = mailer.getSession();
		
		assertThat(session.getDebug()).isTrue();
		assertThat(session.getProperty("mail.smtp.host")).isEqualTo("smtp.default.com");
		assertThat(session.getProperty("mail.smtp.port")).isEqualTo("25");
		assertThat(session.getProperty("mail.transport.protocol")).isEqualTo("smtp");
		assertThat(session.getProperty("mail.smtp.starttls.enable")).isEqualTo("true");
		assertThat(session.getProperty("mail.smtp.starttls.required")).isEqualTo("true");
		assertThat(session.getProperty("mail.smtp.ssl.checkserveridentity")).isEqualTo("true");
		assertThat(session.getProperty("mail.smtp.username")).isEqualTo("username smtp");
		assertThat(session.getProperty("mail.smtp.auth")).isEqualTo("true");
		// the following two are because authentication is needed, otherwise proxy would be straightworward
		assertThat(session.getProperty("mail.smtp.socks.host")).isEqualTo("localhost");
		assertThat(session.getProperty("mail.smtp.socks.port")).isEqualTo("1081");
	}
	
	@Test
	public void createMailSession_MinimalConstructor_WithConfig_OPPORTUNISTIC_TLS() {
		Properties properties = new Properties();
		properties.setProperty(OPPORTUNISTIC_TLS.key(), "false");
		ConfigLoader.loadProperties(properties, true);
		
		Mailer mailer = MailerBuilder.withTransportStrategy(TransportStrategy.SMTP).buildMailer();
		Session session = mailer.getSession();
		
		assertThat(session.getDebug()).isTrue();
		assertThat(session.getProperty("mail.smtp.host")).isEqualTo("smtp.default.com");
		assertThat(session.getProperty("mail.smtp.port")).isEqualTo("25");
		assertThat(session.getProperty("mail.transport.protocol")).isEqualTo("smtp");
		
		assertThat(session.getProperty("mail.smtp.starttls.enable")).isNull();
		assertThat(session.getProperty("mail.smtp.starttls.required")).isNull();
		assertThat(session.getProperty("mail.smtp.ssl.checkserveridentity")).isNull();
		
		assertThat(session.getProperty("mail.smtp.username")).isEqualTo("username smtp");
		assertThat(session.getProperty("mail.smtp.auth")).isEqualTo("true");
		// the following two are because authentication is needed, otherwise proxy would be straightworward
		assertThat(session.getProperty("mail.smtp.socks.host")).isEqualTo("localhost");
		assertThat(session.getProperty("mail.smtp.socks.port")).isEqualTo("1081");
	}
	
	@Test
	public void createMailSession_MinimalConstructor_WithConfig_OPPORTUNISTIC_TLS_Manually_Disabled() {
		Properties properties = new Properties();
		properties.setProperty(OPPORTUNISTIC_TLS.key(), "false");
		ConfigLoader.loadProperties(properties, true);
		
		TransportStrategy.SMTP.setOpportunisticTLS(true);
		
		Mailer mailer = MailerBuilder.withTransportStrategy(TransportStrategy.SMTP).buildMailer();
		Session session = mailer.getSession();
		
		assertThat(session.getDebug()).isTrue();
		assertThat(session.getProperty("mail.smtp.host")).isEqualTo("smtp.default.com");
		assertThat(session.getProperty("mail.smtp.port")).isEqualTo("25");
		assertThat(session.getProperty("mail.transport.protocol")).isEqualTo("smtp");
		
		assertThat(session.getProperty("mail.smtp.starttls.enable")).isEqualTo("true");
		assertThat(session.getProperty("mail.smtp.starttls.required")).isEqualTo("false");
		assertThat(session.getProperty("mail.smtp.ssl.trust")).isEqualTo("*");
		assertThat(session.getProperty("mail.smtp.ssl.checkserveridentity")).isEqualTo("false");
		
		assertThat(session.getProperty("mail.smtp.username")).isEqualTo("username smtp");
		assertThat(session.getProperty("mail.smtp.auth")).isEqualTo("true");
		// the following two are because authentication is needed, otherwise proxy would be straightworward
		assertThat(session.getProperty("mail.smtp.socks.host")).isEqualTo("localhost");
		assertThat(session.getProperty("mail.smtp.socks.port")).isEqualTo("1081");
	}
	
	@Test
	public void createMailSession_MaximumConstructor_WithConfig()
			throws Exception {
		Mailer mailer = createFullyConfiguredMailer(false, "overridden ", SMTP_TLS);
		
		Session session = mailer.getSession();
		
		assertThat(session.getDebug()).isTrue();
		assertThat(session.getProperty("mail.smtp.host")).isEqualTo("overridden smtp host");
		assertThat(session.getProperty("mail.smtp.port")).isEqualTo("25");
		assertThat(session.getProperty("mail.transport.protocol")).isEqualTo("smtp");
		assertThat(session.getProperty("mail.smtp.starttls.enable")).isEqualTo("true");
		assertThat(session.getProperty("mail.smtp.starttls.required")).isEqualTo("true");
		assertThat(session.getProperty("mail.smtp.ssl.checkserveridentity")).isEqualTo("true");
		assertThat(session.getProperty("mail.smtp.username")).isEqualTo("overridden username smtp");
		assertThat(session.getProperty("mail.smtp.auth")).isEqualTo("true");
		// the following two are because authentication is needed, otherwise proxy would be straightworward
		assertThat(session.getProperty("mail.smtp.socks.host")).isEqualTo("localhost");
		assertThat(session.getProperty("mail.smtp.socks.port")).isEqualTo("1081");
		assertThat(session.getProperty("extra1")).isEqualTo("overridden value1");
		assertThat(session.getProperty("extra2")).isEqualTo("overridden value2");
	}
	
	@Test
	public void createMailSession_MaximumConstructor_WithConfig_TLS()
			throws Exception {
		Mailer mailer = createFullyConfiguredMailer(false, "overridden ", SMTPS);
		
		Session session = mailer.getSession();
		
		assertThat(session.getDebug()).isTrue();
		assertThat(session.getProperty("mail.smtps.host")).isEqualTo("overridden smtp host");
		assertThat(session.getProperty("mail.smtps.port")).isEqualTo("25");
		assertThat(session.getProperty("mail.transport.protocol")).isEqualTo("smtps");
		assertThat(session.getProperty("mail.smtps.quitwait")).isEqualTo("false");
		assertThat(session.getProperty("mail.smtps.username")).isEqualTo("overridden username smtp");
		assertThat(session.getProperty("mail.smtps.auth")).isEqualTo("true");
		assertThat(session.getProperty("extra1")).isEqualTo("overridden value1");
		assertThat(session.getProperty("extra2")).isEqualTo("overridden value2");
	}
	
	@Test
	public void testDKIMPriming()
			throws IOException, MessagingException {
		final EmailPopulatingBuilder emailPopulatingBuilder = EmailHelper.createDummyEmailBuilder(true, false, false);
		
		// System.out.println(printBase64Binary(Files.readAllBytes(Paths.get("D:\\keys\\dkim.der")))); // needs jdk 1.7
		String privateDERkeyBase64 =
				"MIICdgIBADANBgkqhkiG9w0BAQEFAASCAmAwggJcAgEAAoGBAMYuC7ZjFBSWJtP6JH8w1deJE+5sLwkUacZcW4MTVQXTM33BzN8Ec64KO1Hk2B9oxkpdunKt"
						+ "BggwbWMlGU5gGu4PpQ20cdPcfBIkUMlQKaakHPPGNYaF9dQaZIRy8XON6g1sOJGALXtUYX1r5hdDH13kC/YBw9f1Dsi2smrB0qabAgMBAAECgYAdWbBuYJoWum4hssg49hiVhT2ob+k"
						+ "/ZQCNWhxLe096P18+3rbiyJwBSI6kgEnpzPChDuSQG0PrbpCkwFfRHbafDIPiMi5b6YZkJoFmmOmBHsewS1VdR/phk+aPQV2SoJ0S0FAGZkOnOkagHfmEMSgjZzTpJouu5NU8mwqz8z"
						+ "/s0QJBAOUnELTMG/Se3Pw4FQ49K49lA81QaMoL63lYIEvc6uSVoJSEcrBFxv5sfJW2LFWs8VIDyTvYzsCjLwZj6nwA3k0CQQDdZgVHX7crlpUxO/cjKtTa/Nq9S6XLv3S6XX3YJJ9/Z"
						+ "pYpqAWJbbR+8scBgVxS+9NLLeHhlx/EvkaZRdLhwRyHAkEAtr1ThkqrFIXHxt9Wczd20HCG+qlgF5gv3WHYx4bSTx2/pBCHgWjzyxtqst1HN7+l5nicdrxsDJVVv+vYJ7FtlQJAWPgG"
						+ "Zwgvs3Rvv7k5NwifQOEbhbZAigAGCF5Jk/Ijpi6zaUn7754GSn2FOzWgxDguUKe/fcgdHBLai/1jIRVZQQJAXF2xzWMwP+TmX44QxK52QHVI8mhNzcnH7A311gWns6AbLcuLA9quwjU"
						+ "YJMRlfXk67lJXCleZL15EpVPrQ34KlA==";
		
		emailPopulatingBuilder.signWithDomainKey(new ByteArrayInputStream(parseBase64Binary(privateDERkeyBase64)), "somemail.com", "select");
		MimeMessage mimeMessage = EmailConverter.emailToMimeMessage(emailPopulatingBuilder.buildEmail());
		// success, signing did not produce an error
		assertThat(mimeMessage).isInstanceOf(DkimMessage.class);
	}
	
	@Test
	public void testParser()
			throws Exception {
		final EmailPopulatingBuilder emailPopulatingBuilderNormal = EmailHelper.createDummyEmailBuilder(true, false, false);
		
		// let's try producing and then consuming a MimeMessage ->
		// (bounce recipient is not part of the Mimemessage but the Envelope and is configured on the Session, so just ignore this)
		emailPopulatingBuilderNormal.clearBounceTo();
		final Email emailNormal = emailPopulatingBuilderNormal.buildEmail();
		final MimeMessage mimeMessage = EmailConverter.emailToMimeMessage(emailNormal);
		final Email emailFromMimeMessage = EmailConverter.mimeMessageToEmail(mimeMessage);
		
		TestDataHelper.fixDresscodeAttachment(emailFromMimeMessage);
		
		assertThat(emailFromMimeMessage).isEqualTo(emailNormal);
	}
	
	private Mailer createFullyConfiguredMailer(boolean authenticateProxy, String prefix, TransportStrategy transportStrategy) {
		MailerRegularBuilder mailerBuilder = MailerBuilder
				.withSMTPServer(prefix + "smtp host", 25, prefix + "username smtp", prefix + "password smtp")
				.withTransportStrategy(transportStrategy)
				.withDebugLogging(true);
		
		if (transportStrategy == SMTP_TLS) {
			if (authenticateProxy) {
				mailerBuilder
						.withProxy(prefix + "proxy host", 1080, prefix + "username proxy", prefix + "password proxy")
						.withProxyBridgePort(999);
			} else {
				mailerBuilder.withProxy(prefix + "proxy host", 1080);
			}
		} else if (transportStrategy == SMTPS) {
			mailerBuilder.clearProxy();
		}
		
		return mailerBuilder
				.withProperty("extra1", prefix + "value1")
				.withProperty("extra2", prefix + "value2")
				.buildMailer();
	}
}