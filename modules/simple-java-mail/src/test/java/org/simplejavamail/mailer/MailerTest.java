package org.simplejavamail.mailer;

import jakarta.mail.Address;
import jakarta.mail.Message;
import jakarta.mail.MessagingException;
import jakarta.mail.Provider;
import jakarta.mail.Session;
import jakarta.mail.URLName;
import jakarta.mail.internet.MimeMessage;
import org.eclipse.angus.mail.smtp.SMTPTransport;
import org.jetbrains.annotations.Nullable;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.simplejavamail.MailException;
import org.simplejavamail.api.email.Email;
import org.simplejavamail.api.email.EmailPopulatingBuilder;
import org.simplejavamail.api.email.config.DkimConfig;
import org.simplejavamail.api.mailer.CustomMailer;
import org.simplejavamail.api.mailer.MailSubmissionReceipt;
import org.simplejavamail.api.mailer.Mailer;
import org.simplejavamail.api.mailer.SmtpServerResponse;
import org.simplejavamail.api.mailer.config.OperationalConfig;
import org.simplejavamail.api.mailer.config.SessionDebugOutput;
import org.simplejavamail.api.mailer.config.TransportStrategy;
import org.simplejavamail.config.ConfigLoader;
import org.simplejavamail.converter.EmailConverter;
import org.simplejavamail.email.EmailBuilder;
import org.simplejavamail.converter.internal.mimemessage.ImmutableDelegatingSMTPMessage;
import org.simplejavamail.mailer.internal.MailerRegularBuilderImpl;
import org.simplejavamail.mailer.internal.SessionBasedEmailToMimeMessageConverter;
import org.simplejavamail.util.TestDataHelper;
import org.simplejavamail.utils.mail.dkim.DkimMessage;
import testutil.ConfigLoaderTestHelper;
import testutil.EmailHelper;

import javax.net.ssl.SSLSocketFactory;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.io.PrintStream;
import java.util.AbstractMap.SimpleEntry;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Base64;
import java.util.GregorianCalendar;
import java.util.List;
import java.util.Properties;
import java.util.UUID;

import static demo.ResourceFolderHelper.determineResourceFolder;
import static jakarta.mail.Message.RecipientType.TO;
import static java.util.Calendar.APRIL;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.simplejavamail.api.mailer.config.TransportStrategy.SMTPS;
import static org.simplejavamail.api.mailer.config.TransportStrategy.SMTP_TLS;
import static org.simplejavamail.config.ConfigLoader.Property.OPPORTUNISTIC_TLS;

@SuppressWarnings("unused")
public class MailerTest {

	private static final String RESOURCES_PKCS = determineResourceFolder("simple-java-mail") + "/test/resources/pkcs12";

	@BeforeEach
	public void restoreOriginalStaticProperties() {
		String s = "simplejavamail.javaxmail.debug=true\n"
				+ "simplejavamail.transportstrategy=SMTP_TLS\n"
				+ "simplejavamail.smtp.host=smtp.default.com\n"
				+ "simplejavamail.smtp.port=25\n"
				+ "simplejavamail.smtp.username=username smtp\n"
				+ "simplejavamail.smtp.password=password smtp\n"
				+ "simplejavamail.javaxmail.debug.out=STDERR\n"
				+ "simplejavamail.proxy.host=proxy.default.com\n"
				+ "simplejavamail.proxy.port=1080\n"
				+ "simplejavamail.proxy.username=username proxy\n"
				+ "simplejavamail.proxy.password=password proxy\n"
				+ "simplejavamail.proxy.socks5bridge.port=1081\n"
				+ "simplejavamail.defaults.trustedhosts=192.168.1.122;mymailserver.com;ix55432y\n"
				+ "simplejavamail.extraproperties.extra-properties-property1=value1\n"
				+ "simplejavamail.extraproperties.extra-properties-property2=value2";

		ConfigLoader.loadProperties(new ByteArrayInputStream(s.getBytes()), false);
	}
	
	@Test
	public void createMailSession_MinimalConstructor_WithoutConfig() {
		ConfigLoaderTestHelper.clearConfigProperties();

		final UUID clusterKey = UUID.randomUUID();
		Mailer mailer = MailerBuilder.withSMTPServer("host", 25, null, null).withClusterKey(clusterKey).buildMailer();
		assertThat(mailer.getOperationalConfig().getSslHostsToTrust()).isEmpty();

		Session session = mailer.getSession();
		
		assertThat(session.getDebug()).isFalse();
		assertThat(mailer.getOperationalConfig().getDebugPrinter()).isNull();
		assertThat(session.getProperty("mail.smtp.host")).isEqualTo("host");
		assertThat(session.getProperty("mail.smtp.port")).isEqualTo("25");
		assertThat(session.getProperty("mail.transport.protocol")).isEqualTo("smtp");
		
		assertThat(session.getProperty("mail.smtp.starttls.enable")).isEqualTo("true");
		assertThat(session.getProperty("mail.smtp.starttls.required")).isEqualTo("false");
		assertThat(session.getProperty("mail.smtp.ssl.trust")).isEqualTo("*");
		assertThat(session.getProperty("mail.smtp.ssl.checkserveridentity")).isEqualTo("true");
		
		assertThat(session.getProperty("mail.smtp.user")).isNull();
		assertThat(session.getProperty("mail.smtp.auth")).isNull();
		assertThat(session.getProperty("mail.smtp.socks.host")).isNull();
		assertThat(session.getProperty("mail.smtp.socks.port")).isNull();

		assertThat(session.getProperty("extra1")).isNull();
		assertThat(session.getProperty("extra2")).isNull();

		assertThat(session.getProperty("extra-properties-property1")).isNull();
		assertThat(session.getProperty("extra-properties-property2")).isNull();

		assertThat(mailer.getOperationalConfig().getClusterKey()).isEqualTo(clusterKey);

		Mailer otherMailerSameSession = MailerBuilder.usingSession(session).withClusterKey(clusterKey).buildMailer();
		assertThat(session.getProperties()).isEqualTo(otherMailerSameSession.getSession().getProperties());

		Mailer otherMailerOtherSession = MailerBuilder.withSMTPServer("host", 25).withClusterKey(clusterKey).buildMailer();
		assertThat(session.getProperties()).isNotEqualTo(otherMailerOtherSession.getSession().getProperties());

		SessionBasedEmailToMimeMessageConverter.unprimeSession(session);
		SessionBasedEmailToMimeMessageConverter.unprimeSession(otherMailerOtherSession.getSession());
		assertThat(session.getProperties()).isEqualTo(otherMailerOtherSession.getSession().getProperties());
	}
	
	@Test
	public void createMailSession_AnonymousProxyConstructor_WithoutConfig() {
		ConfigLoaderTestHelper.clearConfigProperties();

		Mailer mailer = createFullyConfiguredMailerBuilder(false, "", SMTP_TLS).buildMailer();
		
		Session session = mailer.getSession();
		
		assertThat(session.getDebug()).isTrue();
		assertThat(session.getProperty("mail.smtp.host")).isEqualTo("smtp host");
		assertThat(session.getProperty("mail.smtp.port")).isEqualTo("25");
		assertThat(session.getProperty("mail.transport.protocol")).isEqualTo("smtp");
		
		assertThat(session.getProperty("mail.smtp.starttls.enable")).isEqualTo("true");
		assertThat(session.getProperty("mail.smtp.starttls.required")).isEqualTo("true");
		assertThat(session.getProperty("mail.smtp.ssl.checkserveridentity")).isEqualTo("true");
		
		assertThat(session.getProperty("mail.smtp.user")).isEqualTo("username smtp");
		assertThat(session.getProperty("mail.smtp.auth")).isEqualTo("true");
		assertThat(session.getProperty("mail.smtp.socks.host")).isEqualTo("proxy host");
		assertThat(session.getProperty("mail.smtp.socks.port")).isEqualTo("1080");
		assertThat(session.getProperty("extra1")).isEqualTo("value1");
		assertThat(session.getProperty("extra2")).isEqualTo("value2");
		assertThat(session.getProperty("extra-properties-property1")).isNull();
		assertThat(session.getProperty("extra-properties-property2")).isNull();
	}
	
	@Test
	public void createMailSession_MaximumConstructor_WithoutConfig() {
		ConfigLoaderTestHelper.clearConfigProperties();

		Mailer mailer = createFullyConfiguredMailerBuilder(true, "", SMTP_TLS).buildMailer();
		
		Session session = mailer.getSession();
		
		assertThat(session.getDebug()).isTrue();
		assertThat(session.getProperty("mail.smtp.host")).isEqualTo("smtp host");
		assertThat(session.getProperty("mail.smtp.port")).isEqualTo("25");
		assertThat(session.getProperty("mail.transport.protocol")).isEqualTo("smtp");
		assertThat(session.getProperty("mail.smtp.starttls.enable")).isEqualTo("true");
		assertThat(session.getProperty("mail.smtp.starttls.required")).isEqualTo("true");
		assertThat(session.getProperty("mail.smtp.ssl.checkserveridentity")).isEqualTo("true");
		assertThat(session.getProperty("mail.smtp.user")).isEqualTo("username smtp");
		assertThat(session.getProperty("mail.smtp.auth")).isEqualTo("true");
		// the following two are because authentication is needed, otherwise proxy would be straightworward
		assertThat(session.getProperty("mail.smtp.socks.host")).isEqualTo("localhost");
		assertThat(session.getProperty("mail.smtp.socks.port")).isEqualTo("999");
		assertThat(session.getProperty("extra1")).isEqualTo("value1");
		assertThat(session.getProperty("extra2")).isEqualTo("value2");
		assertThat(session.getProperty("extra-properties-property1")).isNull();
		assertThat(session.getProperty("extra-properties-property2")).isNull();
	}
	
	@Test
	public void createMailSession_MinimalConstructor_WithConfig() {
		Mailer mailer = MailerBuilder.buildMailer();
		Session session = mailer.getSession();

		assertThat(mailer.getOperationalConfig().getSslHostsToTrust()).containsExactlyInAnyOrder(
				"192.168.1.122", "mymailserver.com", "ix55432y");
		
		assertThat(session.getDebug()).isTrue();
		assertThat(session.getDebugOut()).isSameAs(System.err);
		assertThat(session.getProperty("mail.smtp.host")).isEqualTo("smtp.default.com");
		assertThat(session.getProperty("mail.smtp.port")).isEqualTo("25");
		assertThat(session.getProperty("mail.transport.protocol")).isEqualTo("smtp");
		assertThat(session.getProperty("mail.smtp.starttls.enable")).isEqualTo("true");
		assertThat(session.getProperty("mail.smtp.starttls.required")).isEqualTo("true");
		assertThat(session.getProperty("mail.smtp.ssl.checkserveridentity")).isEqualTo("true");
		assertThat(session.getProperty("mail.smtp.user")).isEqualTo("username smtp");
		assertThat(session.getProperty("mail.smtp.auth")).isEqualTo("true");
		// the following two are because authentication is needed, otherwise proxy would be straightworward
		assertThat(session.getProperty("mail.smtp.socks.host")).isEqualTo("localhost");
		assertThat(session.getProperty("mail.smtp.socks.port")).isEqualTo("1081");
		assertThat(session.getProperty("extra1")).isNull();
		assertThat(session.getProperty("extra2")).isNull();
		assertThat(session.getProperty("extra-properties-property1")).isEqualTo("value1");
		assertThat(session.getProperty("extra-properties-property2")).isEqualTo("value2");
	}

	@Test
	public void createMailSession_WithJavaDebugPrinter() {
		ConfigLoaderTestHelper.clearConfigProperties();
		PrintStream debugPrinter = new PrintStream(new ByteArrayOutputStream());

		Mailer mailer = MailerBuilder.withSMTPServer("host", 25, null, null)
				.withDebugLogging(true)
				.withDebugPrinter(debugPrinter)
				.buildMailer();

		assertThat(mailer.getSession().getDebug()).isTrue();
		assertThat(mailer.getSession().getDebugOut()).isSameAs(debugPrinter);
		assertThat(mailer.getOperationalConfig().getDebugPrinter()).isSameAs(debugPrinter);
	}

	@Test
	public void createMailSession_WithBuiltInDebugOutput() {
		ConfigLoaderTestHelper.clearConfigProperties();

		Mailer mailer = MailerBuilder.withSMTPServer("host", 25, null, null)
				.withDebugLogging(true)
				.withDebugOutput(SessionDebugOutput.STDERR)
				.buildMailer();

		assertThat(mailer.getSession().getDebug()).isTrue();
		assertThat(mailer.getSession().getDebugOut()).isSameAs(System.err);
		assertThat(mailer.getOperationalConfig().getDebugPrinter()).isSameAs(System.err);
	}

	@Test
	public void createMailSession_WithBuiltInSlf4jDebugOutput() {
		ConfigLoaderTestHelper.clearConfigProperties();

		Mailer mailer = MailerBuilder.withSMTPServer("host", 25, null, null)
				.withDebugLogging(true)
				.withDebugOutput(SessionDebugOutput.SLF4J)
				.buildMailer();

		assertThat(mailer.getSession().getDebug()).isTrue();
		assertThat(mailer.getSession().getDebugOut()).isSameAs(mailer.getOperationalConfig().getDebugPrinter());
		assertThat(mailer.getSession().getDebugOut()).isNotSameAs(System.out);
		assertThat(mailer.getSession().getDebugOut()).isNotSameAs(System.err);
	}

	@Test
	public void createMailSession_MinimalConstructor_WithConfig_OPPORTUNISTIC_TLS() {
		Properties properties = new Properties();
		properties.setProperty(OPPORTUNISTIC_TLS.key(), "false");
		properties.setProperty("simplejavamail.extraproperties.extra-properties-property2", "override");
		ConfigLoader.loadProperties(properties, true);

		Mailer mailer = MailerBuilder.withTransportStrategy(TransportStrategy.SMTP).buildMailer();
		Session session = mailer.getSession();

		assertThat(session.getDebug()).isTrue();
		assertThat(session.getProperty("mail.smtp.host")).isEqualTo("smtp.default.com");
		assertThat(session.getProperty("mail.smtp.port")).isEqualTo("25");
		assertThat(session.getProperty("mail.transport.protocol")).isEqualTo("smtp");

		assertThat(session.getProperty("mail.smtp.starttls.enable")).isNull();
		assertThat(session.getProperty("mail.smtp.starttls.required")).isNull();
		assertThat(session.getProperty("mail.smtp.ssl.checkserveridentity")).isEqualTo("true");

		assertThat(session.getProperty("mail.smtp.user")).isEqualTo("username smtp");
		assertThat(session.getProperty("mail.smtp.auth")).isEqualTo("true");
		// the following two are because authentication is needed, otherwise proxy would be straightworward
		assertThat(session.getProperty("mail.smtp.socks.host")).isEqualTo("localhost");
		assertThat(session.getProperty("mail.smtp.socks.port")).isEqualTo("1081");
		assertThat(session.getProperty("extra-properties-property1")).isEqualTo("value1");
		assertThat(session.getProperty("extra-properties-property2")).isEqualTo("override");
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
		assertThat(session.getProperty("mail.smtp.ssl.checkserveridentity")).isEqualTo("true");
		
		assertThat(session.getProperty("mail.smtp.user")).isEqualTo("username smtp");
		assertThat(session.getProperty("mail.smtp.auth")).isEqualTo("true");
		// the following two are because authentication is needed, otherwise proxy would be straightworward
		assertThat(session.getProperty("mail.smtp.socks.host")).isEqualTo("localhost");
		assertThat(session.getProperty("mail.smtp.socks.port")).isEqualTo("1081");
	}
	
	@Test
	public void createMailSession_MaximumConstructor_WithConfig() {
		Mailer mailer = createFullyConfiguredMailerBuilder(false, "overridden ", SMTP_TLS).buildMailer();
		
		Session session = mailer.getSession();
		
		assertThat(session.getDebug()).isTrue();
		assertThat(session.getProperty("mail.smtp.host")).isEqualTo("overridden smtp host");
		assertThat(session.getProperty("mail.smtp.port")).isEqualTo("25");
		assertThat(session.getProperty("mail.transport.protocol")).isEqualTo("smtp");
		assertThat(session.getProperty("mail.smtp.starttls.enable")).isEqualTo("true");
		assertThat(session.getProperty("mail.smtp.starttls.required")).isEqualTo("true");
		assertThat(session.getProperty("mail.smtp.ssl.checkserveridentity")).isEqualTo("true");
		assertThat(session.getProperty("mail.smtp.user")).isEqualTo("overridden username smtp");
		assertThat(session.getProperty("mail.smtp.auth")).isEqualTo("true");
		// the following two are because authentication is needed, otherwise proxy would be straightworward
		assertThat(session.getProperty("mail.smtp.socks.host")).isEqualTo("localhost");
		assertThat(session.getProperty("mail.smtp.socks.port")).isEqualTo("1081");
		assertThat(session.getProperty("extra1")).isEqualTo("overridden value1");
		assertThat(session.getProperty("extra2")).isEqualTo("overridden value2");
	}
	
	@Test
	public void createMailSession_MaximumConstructor_WithConfig_TLS() {
		Mailer mailer = createFullyConfiguredMailerBuilder(false, "overridden ", SMTPS).buildMailer();
		
		Session session = mailer.getSession();
		
		assertThat(session.getDebug()).isTrue();
		assertThat(session.getProperty("mail.smtps.host")).isEqualTo("overridden smtp host");
		assertThat(session.getProperty("mail.smtps.port")).isEqualTo("25");
		assertThat(session.getProperty("mail.transport.protocol")).isEqualTo("smtps");
		assertThat(session.getProperty("mail.smtps.quitwait")).isEqualTo("false");
		assertThat(session.getProperty("mail.smtps.user")).isEqualTo("overridden username smtp");
		assertThat(session.getProperty("mail.smtps.auth")).isEqualTo("true");
		assertThat(session.getProperty("extra1")).isEqualTo("overridden value1");
		assertThat(session.getProperty("extra2")).isEqualTo("overridden value2");
	}

	@Test
	public void testDKIMPriming()
			throws IOException {
		final EmailPopulatingBuilder emailPopulatingBuilder = EmailHelper
				.createDummyEmailBuilder(true, false, false, true, false, false)
				.from("Mr Sender", "mr.sender@supersecret-testing-domain.com");

		// System.out.println(printBase64Binary(Files.readAllBytes(Paths.get("D:\\keys\\dkim.der")))); // needs jdk 1.7
		String privateDERkeyBase64 =
				"MIICdgIBADANBgkqhkiG9w0BAQEFAASCAmAwggJcAgEAAoGBAMYuC7ZjFBSWJtP6JH8w1deJE+5sLwkUacZcW4MTVQXTM33BzN8Ec64KO1Hk2B9oxkpdunKt"
						+ "BggwbWMlGU5gGu4PpQ20cdPcfBIkUMlQKaakHPPGNYaF9dQaZIRy8XON6g1sOJGALXtUYX1r5hdDH13kC/YBw9f1Dsi2smrB0qabAgMBAAECgYAdWbBuYJoWum4hssg49hiVhT2ob+k"
						+ "/ZQCNWhxLe096P18+3rbiyJwBSI6kgEnpzPChDuSQG0PrbpCkwFfRHbafDIPiMi5b6YZkJoFmmOmBHsewS1VdR/phk+aPQV2SoJ0S0FAGZkOnOkagHfmEMSgjZzTpJouu5NU8mwqz8z"
						+ "/s0QJBAOUnELTMG/Se3Pw4FQ49K49lA81QaMoL63lYIEvc6uSVoJSEcrBFxv5sfJW2LFWs8VIDyTvYzsCjLwZj6nwA3k0CQQDdZgVHX7crlpUxO/cjKtTa/Nq9S6XLv3S6XX3YJJ9/Z"
						+ "pYpqAWJbbR+8scBgVxS+9NLLeHhlx/EvkaZRdLhwRyHAkEAtr1ThkqrFIXHxt9Wczd20HCG+qlgF5gv3WHYx4bSTx2/pBCHgWjzyxtqst1HN7+l5nicdrxsDJVVv+vYJ7FtlQJAWPgG"
						+ "Zwgvs3Rvv7k5NwifQOEbhbZAigAGCF5Jk/Ijpi6zaUn7754GSn2FOzWgxDguUKe/fcgdHBLai/1jIRVZQQJAXF2xzWMwP+TmX44QxK52QHVI8mhNzcnH7A311gWns6AbLcuLA9quwjU"
						+ "YJMRlfXk67lJXCleZL15EpVPrQ34KlA==";

		emailPopulatingBuilder.signWithDomainKey(DkimConfig.builder()
						.dkimPrivateKeyData(new ByteArrayInputStream(Base64.getDecoder().decode(privateDERkeyBase64)))
						.dkimSigningDomain("supersecret-testing-domain.com")
						.dkimSelector("dkim1")
						.excludedHeadersFromDkimDefaultSigningList("Reply-To")
				.build());
		MimeMessage dkimSignedMessage = EmailConverter.emailToMimeMessage(emailPopulatingBuilder.buildEmail());
		// success, hooking into the DKIM library did not produce an error
		assertThat(dkimSignedMessage).isInstanceOf(ImmutableDelegatingSMTPMessage.class);
		assertThat(((ImmutableDelegatingSMTPMessage) dkimSignedMessage).getDelegate()).isInstanceOf(DkimMessage.class);

		// just a quick double check we don't have a DKIM signature yet:
		assertThat(EmailConverter.mimeMessageToEmail(dkimSignedMessage).getHeaders()).doesNotContainKey("DKIM-Signature");

		// now trigger the actual signing:
		String eml = EmailConverter.mimeMessageToEML(dkimSignedMessage);
		Email dkimSignedEmail = EmailConverter.emlToEmail(eml);
		// success, signing itself did not produce an error either
		assertThat(dkimSignedEmail.getHeaders()).containsKey("DKIM-Signature");
		assertThat(dkimSignedEmail.getHeaders().get("DKIM-Signature"))
				.isInstanceOf(List.class)
				.hasSize(1)
				.first()
				.isInstanceOf(String.class)
				.asString()
				.contains(
						"v=1;",
						"a=rsa-sha256;",
						"q=dns/txt;",
						"c=relaxed/relaxed;",
						"s=dkim1;",
						"d=supersecret-testing-domain.com;",
						"i=mr.sender@supersecret-testing-domain.com;",
						"h=Content-Type:MIME-Version:Subject:Message-ID:To:"+/*Reply-To:*/"From:Date;");
	}

	@Test
	public void testSSLSocketFactoryClassConfig() {
		final Mailer mailer = MailerBuilder
				.withSMTPServer("host", 25, null, null)
				.withCustomSSLFactoryClass("teh_class")
				.buildMailer();

		final Session session = mailer.getSession();

		assertThat(session.getProperties()).contains(new SimpleEntry<String, Object>("mail.smtp.ssl.socketFactory.class", "teh_class"));
		assertThat(session.getProperties()).doesNotContainKey("mail.smtp.ssl.socketFactory");
	}

	@Test
	public void testSSLSocketFactoryClassConfig_SMTPS() {
		final Mailer mailer = MailerBuilder
				.withSMTPServer("host", 25, null, null)
				.withTransportStrategy(SMTPS)
				.clearProxy()
				.withCustomSSLFactoryClass("teh_class")
				.buildMailer();

		final Session session = mailer.getSession();

		assertThat(session.getProperties()).contains(new SimpleEntry<String, Object>("mail.smtps.ssl.socketFactory.class", "teh_class"));
		assertThat(session.getProperties()).doesNotContainKey("mail.smtps.ssl.socketFactory");
		assertThat(session.getProperties()).doesNotContainKey("mail.smtp.ssl.socketFactory.class");
	}

	@Test
	public void testSSLSocketFactoryInstanceConfig() {
		final SSLSocketFactory mockFactory = mock(SSLSocketFactory.class);

		final Mailer mailer = MailerBuilder
				.withSMTPServer("host", 25, null, null)
				.withCustomSSLFactoryInstance(mockFactory)
				.buildMailer();

		final Session session = mailer.getSession();

		assertThat(session.getProperties()).contains(new SimpleEntry<String, Object>("mail.smtp.ssl.socketFactory", mockFactory));
		assertThat(session.getProperties()).doesNotContainKey("mail.smtp.ssl.socketFactory.class");
	}

	@Test
	public void testSSLSocketFactoryInstanceConfig_SMTPS() {
		final SSLSocketFactory mockFactory = mock(SSLSocketFactory.class);

		final Mailer mailer = MailerBuilder
				.withSMTPServer("host", 25, null, null)
				.withTransportStrategy(SMTPS)
				.clearProxy()
				.withCustomSSLFactoryInstance(mockFactory)
				.buildMailer();

		final Session session = mailer.getSession();

		assertThat(session.getProperties()).contains(new SimpleEntry<String, Object>("mail.smtps.ssl.socketFactory", mockFactory));
		assertThat(session.getProperties()).doesNotContainKey("mail.smtps.ssl.socketFactory.class");
		assertThat(session.getProperties()).doesNotContainKey("mail.smtp.ssl.socketFactory");
	}

	@Test
	public void testSSLSocketFactoryCombinedConfig() {
		final SSLSocketFactory mockFactory = mock(SSLSocketFactory.class);

		final Mailer mailer = MailerBuilder
				.withSMTPServer("host", 25, null, null)
				.withCustomSSLFactoryInstance(mockFactory)
				.withCustomSSLFactoryClass("teh_class")
				.buildMailer();

		final Session session = mailer.getSession();

		assertThat(session.getProperties()).contains(new SimpleEntry<String, Object>("mail.smtp.ssl.socketFactory", mockFactory));
		assertThat(session.getProperties()).doesNotContainKey("mail.smtp.ssl.socketFactory.class");
	}

	@Test
	public void testDKIMPrimingAndSmimeCombo()
			throws IOException {
		final EmailPopulatingBuilder emailPopulatingBuilder = EmailHelper.createDummyEmailBuilder(true, false, false, true, false, false);

		// System.out.println(printBase64Binary(Files.readAllBytes(Paths.get("D:\\keys\\dkim.der")))); // needs jdk 1.7
		String privateDERkeyBase64 =
				"MIICdgIBADANBgkqhkiG9w0BAQEFAASCAmAwggJcAgEAAoGBAMYuC7ZjFBSWJtP6JH8w1deJE+5sLwkUacZcW4MTVQXTM33BzN8Ec64KO1Hk2B9oxkpdunKt"
						+ "BggwbWMlGU5gGu4PpQ20cdPcfBIkUMlQKaakHPPGNYaF9dQaZIRy8XON6g1sOJGALXtUYX1r5hdDH13kC/YBw9f1Dsi2smrB0qabAgMBAAECgYAdWbBuYJoWum4hssg49hiVhT2ob+k"
						+ "/ZQCNWhxLe096P18+3rbiyJwBSI6kgEnpzPChDuSQG0PrbpCkwFfRHbafDIPiMi5b6YZkJoFmmOmBHsewS1VdR/phk+aPQV2SoJ0S0FAGZkOnOkagHfmEMSgjZzTpJouu5NU8mwqz8z"
						+ "/s0QJBAOUnELTMG/Se3Pw4FQ49K49lA81QaMoL63lYIEvc6uSVoJSEcrBFxv5sfJW2LFWs8VIDyTvYzsCjLwZj6nwA3k0CQQDdZgVHX7crlpUxO/cjKtTa/Nq9S6XLv3S6XX3YJJ9/Z"
						+ "pYpqAWJbbR+8scBgVxS+9NLLeHhlx/EvkaZRdLhwRyHAkEAtr1ThkqrFIXHxt9Wczd20HCG+qlgF5gv3WHYx4bSTx2/pBCHgWjzyxtqst1HN7+l5nicdrxsDJVVv+vYJ7FtlQJAWPgG"
						+ "Zwgvs3Rvv7k5NwifQOEbhbZAigAGCF5Jk/Ijpi6zaUn7754GSn2FOzWgxDguUKe/fcgdHBLai/1jIRVZQQJAXF2xzWMwP+TmX44QxK52QHVI8mhNzcnH7A311gWns6AbLcuLA9quwjU"
						+ "YJMRlfXk67lJXCleZL15EpVPrQ34KlA==";

		emailPopulatingBuilder.signWithDomainKey(DkimConfig.builder()
				.dkimPrivateKeyData(new ByteArrayInputStream(Base64.getDecoder().decode(privateDERkeyBase64)))
				.dkimSigningDomain("somemail.com")
				.dkimSelector("select")
				.build());
		emailPopulatingBuilder.signWithSmime(new File(RESOURCES_PKCS + "/smime_keystore.pkcs12"), "letmein", "smime_test_user_alias_rsa", "letmein", null);
		emailPopulatingBuilder.encryptWithSmime(new File(RESOURCES_PKCS + "/smime_test_user.pem.standard.crt"), null, null);

		MimeMessage mimeMessage = EmailConverter.emailToMimeMessage(emailPopulatingBuilder.buildEmail());
		// success, signing did not produce an error
		assertThat(mimeMessage).isInstanceOf(ImmutableDelegatingSMTPMessage.class);
		assertThat(((ImmutableDelegatingSMTPMessage) mimeMessage).getDelegate()).isInstanceOf(DkimMessage.class);
	}
	
	@Test
	public void testParser()
			throws Exception {
		final EmailPopulatingBuilder emailPopulatingBuilderNormal = EmailHelper.createDummyEmailBuilder(true, false, false, true, false, false);
		
		// let's try producing and then consuming a MimeMessage ->
		// (bounce recipient is not part of the Mimemessage but the Envelope and is not received back on the MimeMessage
		emailPopulatingBuilderNormal.clearBounceTo();
		emailPopulatingBuilderNormal.fixingSentDate(new GregorianCalendar(2011, APRIL, 1, 3, 51).getTime()); // always generated when producing mime message
		final Email emailNormal = emailPopulatingBuilderNormal.buildEmail();
		final MimeMessage mimeMessage = EmailConverter.emailToMimeMessage(emailNormal);
		final Email emailFromMimeMessage = EmailConverter.mimeMessageToEmail(mimeMessage);
		
		TestDataHelper.retrofitLostOriginalAttachmentNames(emailFromMimeMessage);

		assertThat(emailFromMimeMessage).isEqualTo(emailNormal);
	}

	@Test
	public void testCustomMailer_sendEmail() throws IOException {
		final Email email = EmailHelper.createDummyEmailBuilder(true, false, false, true, false, false).buildEmail();
		final CustomMailer customMailerMock = mock(CustomMailer.class);

		getMailerWithCustomMailer(customMailerMock).sendMail(email);

		verify(customMailerMock).sendMessage(any(OperationalConfig.class), any(Session.class), any(Email.class), any(MimeMessage.class));
		verifyNoMoreInteractions(customMailerMock);
	}

	@Test
	public void testSendMailAndGetReceipt_returnsSmtpServerResponse() throws Exception {
		ConfigLoaderTestHelper.clearConfigProperties();
		CountingTransport.reset();

		final Session session = createCountingTransportSession();

		MailSubmissionReceipt receipt;
		try (Mailer mailer = MailerBuilder.usingSession(session).buildMailer()) {
			receipt = mailer.sendMailAndGetReceipt(createBatchEmail("Receipt email", "receipt@example.com"), false).get();
		}

		assertThat(CountingTransport.connectCount).isEqualTo(1);
		assertThat(CountingTransport.closeCount).isEqualTo(1);
		assertThat(CountingTransport.sentMessages).hasSize(1);
		assertThat(receipt.getEmailId()).isEqualTo(CountingTransport.sentMessages.get(0).getMessageID());
		assertThat(receipt.getSubmittedAt()).isNotNull();
		assertThat(receipt.isAcceptedByServer()).isTrue();
		assertThat(receipt.getSmtpResponse()).isPresent();
		SmtpServerResponse smtpResponse = receipt.getSmtpResponse().get();
		assertThat(smtpResponse.getReturnCode()).isEqualTo(250);
		assertThat(smtpResponse.getResponse()).isEqualTo("250 queued as simple-java-mail-test-1");
		assertThat(smtpResponse.isPositiveCompletionReply()).isTrue();
	}

	@Test
	public void testSendMailAndGetReceipt_asyncCompletesWithReceipt() throws Exception {
		ConfigLoaderTestHelper.clearConfigProperties();
		CountingTransport.reset();

		final Session session = createCountingTransportSession();

		MailSubmissionReceipt receipt;
		try (Mailer mailer = MailerBuilder.usingSession(session).buildMailer()) {
			receipt = mailer.sendMailAndGetReceipt(createBatchEmail("Async receipt email", "receipt@example.com"), true).get();
		}

		assertThat(CountingTransport.connectCount).isEqualTo(1);
		assertThat(CountingTransport.closeCount).isEqualTo(1);
		assertThat(receipt.isAcceptedByServer()).isTrue();
		assertThat(receipt.getSmtpResponse()).isPresent();
		assertThat(receipt.getSmtpResponse().get().getResponse()).isEqualTo("250 queued as simple-java-mail-test-1");
	}

	@Test
	public void testSendMailAndGetReceipt_customMailerHasNoSmtpResponse() throws Exception {
		final Email email = EmailHelper.createDummyEmailBuilder(true, false, false, true, false, false).buildEmail();
		final CustomMailer customMailerMock = mock(CustomMailer.class);

		MailSubmissionReceipt receipt;
		try (Mailer mailer = getMailerWithCustomMailer(customMailerMock)) {
			receipt = mailer.sendMailAndGetReceipt(email, false).get();
		}

		assertThat(receipt.getEmailId()).isEqualTo(email.getId());
		assertThat(receipt.isAcceptedByServer()).isFalse();
		assertThat(receipt.getSmtpResponse()).isNotPresent();
		verify(customMailerMock).sendMessage(any(OperationalConfig.class), any(Session.class), any(Email.class), any(MimeMessage.class));
		verifyNoMoreInteractions(customMailerMock);
	}

	@Test
	public void testSimpleBatch_sendEmails_usesSingleTransportConnection() throws Exception {
		ConfigLoaderTestHelper.clearConfigProperties();
		CountingTransport.reset();

		final Session session = createCountingTransportSession();

		try (Mailer mailer = MailerBuilder.usingSession(session).buildMailer()) {
			mailer.sendMailsInSimpleBatch(Arrays.asList(
					createBatchEmail("First batch email", "first@example.com"),
					createBatchEmail("Second batch email", "second@example.com")), false);
		}

		assertThat(CountingTransport.connectCount).isEqualTo(1);
		assertThat(CountingTransport.closeCount).isEqualTo(1);
		assertThat(CountingTransport.sentMessages).hasSize(2);
		assertThat(CountingTransport.sentMessages.get(0).getSubject()).isEqualTo("First batch email");
		assertThat(CountingTransport.sentMessages.get(1).getSubject()).isEqualTo("Second batch email");
		assertThat(CountingTransport.sentRecipients.get(0)).extracting(Address::toString).containsExactly("first@example.com");
		assertThat(CountingTransport.sentRecipients.get(1)).extracting(Address::toString).containsExactly("second@example.com");
	}

	@Test
	public void testOpenConnection_sendEmails_allowsCallerCheckpointingBetweenSends() throws Exception {
		ConfigLoaderTestHelper.clearConfigProperties();
		CountingTransport.reset();
		final List<String> markedSent = new ArrayList<>();

		final Session session = createCountingTransportSession();

		try (Mailer mailer = MailerBuilder.usingSession(session).buildMailer()) {
			mailer.withOpenConnection(sender -> {
				sender.sendMail(createBatchEmail("First database email", "first@example.com"));
				markedSent.add("first");

				sender.sendMail(createBatchEmail("Second database email", "second@example.com"));
				markedSent.add("second");
			});
		}

		assertThat(markedSent).containsExactly("first", "second");
		assertThat(CountingTransport.connectCount).isEqualTo(1);
		assertThat(CountingTransport.closeCount).isEqualTo(1);
		assertThat(CountingTransport.sentMessages).hasSize(2);
		assertThat(CountingTransport.sentMessages.get(0).getSubject()).isEqualTo("First database email");
		assertThat(CountingTransport.sentMessages.get(1).getSubject()).isEqualTo("Second database email");
	}

	@Test
	public void testOpenConnection_sendMailAndGetReceipt_returnsSmtpServerResponses() throws Exception {
		ConfigLoaderTestHelper.clearConfigProperties();
		CountingTransport.reset();
		final List<MailSubmissionReceipt> receipts = new ArrayList<>();

		final Session session = createCountingTransportSession();

		try (Mailer mailer = MailerBuilder.usingSession(session).buildMailer()) {
			mailer.withOpenConnection(sender -> {
				receipts.add(sender.sendMailAndGetReceipt(createBatchEmail("First database email", "first@example.com")));
				receipts.add(sender.sendMailAndGetReceipt(createBatchEmail("Second database email", "second@example.com")));
			});
		}

		assertThat(CountingTransport.connectCount).isEqualTo(1);
		assertThat(CountingTransport.closeCount).isEqualTo(1);
		assertThat(CountingTransport.sentMessages).hasSize(2);
		assertThat(receipts).hasSize(2);
		assertThat(receipts).extracting(MailSubmissionReceipt::isAcceptedByServer).containsExactly(true, true);
		assertThat(receipts.get(0).getSmtpResponse().get().getResponse()).isEqualTo("250 queued as simple-java-mail-test-1");
		assertThat(receipts.get(1).getSmtpResponse().get().getResponse()).isEqualTo("250 queued as simple-java-mail-test-2");
	}

	@Test
	public void testOpenConnection_sendEmails_propagatesCallerCheckedException() throws Exception {
		ConfigLoaderTestHelper.clearConfigProperties();
		CountingTransport.reset();

		final IOException checkpointFailure = new IOException("database unavailable");
		final Session session = createCountingTransportSession();

		try (Mailer mailer = MailerBuilder.usingSession(session).buildMailer()) {
			assertThatThrownBy(() -> mailer.withOpenConnection(sender -> {
						sender.sendMail(createBatchEmail("First database email", "first@example.com"));
						throw checkpointFailure;
					}))
					.isSameAs(checkpointFailure);
		}

		assertThat(CountingTransport.connectCount).isEqualTo(1);
		assertThat(CountingTransport.closeCount).isEqualTo(1);
		assertThat(CountingTransport.sentMessages).hasSize(1);
	}

	@Test
	public void testOpenConnection_sendEmails_propagatesCallerRuntimeException() throws Exception {
		ConfigLoaderTestHelper.clearConfigProperties();
		CountingTransport.reset();

		final IllegalStateException checkpointFailure = new IllegalStateException("database unavailable");
		final Session session = createCountingTransportSession();

		try (Mailer mailer = MailerBuilder.usingSession(session).buildMailer()) {
			assertThatThrownBy(() -> mailer.withOpenConnection(sender -> {
						sender.sendMail(createBatchEmail("First database email", "first@example.com"));
						throw checkpointFailure;
					}))
					.isSameAs(checkpointFailure);
		}

		assertThat(CountingTransport.connectCount).isEqualTo(1);
		assertThat(CountingTransport.closeCount).isEqualTo(1);
		assertThat(CountingTransport.sentMessages).hasSize(1);
	}

	@Test
	public void testOpenConnection_sendEmails_rejectsCustomMailer() throws Exception {
		final CustomMailer customMailerMock = mock(CustomMailer.class);

		try (Mailer mailer = getMailerWithCustomMailer(customMailerMock)) {
			assertThatThrownBy(() -> mailer.withOpenConnection(sender -> {
			}))
					.isInstanceOf(MailException.class)
					.hasMessageContaining("custom mailer");
		}
	}

	@Test
	public void testSimpleBatch_sendEmails_usesCustomMailer() {
		final Email email = EmailHelper.createDummyEmailBuilder(true, false, false, true, false, false).buildEmail();
		final CustomMailer customMailerMock = mock(CustomMailer.class);

		getMailerWithCustomMailer(customMailerMock).sendMailsInSimpleBatch(Arrays.asList(email, email), false);

		verify(customMailerMock, times(2)).sendMessage(any(OperationalConfig.class), any(Session.class), any(Email.class), any(MimeMessage.class));
		verifyNoMoreInteractions(customMailerMock);
	}

	@Test
	public void testCustomMailer_testConnection() {
		final CustomMailer customMailerMock = mock(CustomMailer.class);

		getMailerWithCustomMailer(customMailerMock).testConnection();

		verify(customMailerMock).testConnection(any(OperationalConfig.class), any(Session.class));
		verifyNoMoreInteractions(customMailerMock);
	}

	private Mailer getMailerWithCustomMailer(final CustomMailer customMailerMock) {
		return createFullyConfiguredMailerBuilder(false, "", null)
				.withCustomMailer(customMailerMock)
				.buildMailer();
	}

	private static Session createCountingTransportSession() throws MessagingException {
		final Properties properties = new Properties();
		properties.setProperty("mail.transport.protocol", "smtp");
		properties.setProperty("mail.smtp.host", "localhost");
		final Session session = Session.getInstance(properties);
		final Provider provider = new Provider(Provider.Type.TRANSPORT, "smtp", CountingTransport.class.getName(), "Simple Java Mail", "test");
		session.addProvider(provider);
		session.setProvider(provider);
		return session;
	}

	private static Email createBatchEmail(final String subject, final String recipient) {
		return EmailBuilder.startingBlank()
				.from("sender@example.com")
				.withRecipients(null, false, TO, recipient)
				.withSubject(subject)
				.withPlainText("Simple batch body")
				.buildEmail();
	}

	public static class CountingTransport extends SMTPTransport {
		private static int connectCount;
		private static int closeCount;
		private static final List<MimeMessage> sentMessages = new ArrayList<>();
		private static final List<Address[]> sentRecipients = new ArrayList<>();
		private int lastReturnCode = -1;
		@Nullable private String lastServerResponse;

		public CountingTransport(final Session session, final URLName urlName) {
			super(session, urlName);
		}

		private static void reset() {
			connectCount = 0;
			closeCount = 0;
			sentMessages.clear();
			sentRecipients.clear();
		}

		@Override
		protected boolean protocolConnect(final String host, final int port, final String user, final String password) {
			connectCount++;
			return true;
		}

		@Override
		public void sendMessage(final Message message, final Address[] addresses)
				throws MessagingException {
			sentMessages.add((MimeMessage) message);
			sentRecipients.add(addresses);
			lastReturnCode = 250;
			lastServerResponse = "250 queued as simple-java-mail-test-" + sentMessages.size();
		}

		@Override
		public synchronized void close() {
			closeCount++;
			setConnected(false);
		}

		@Override
		public synchronized int getLastReturnCode() {
			return lastReturnCode;
		}

		@Override
		public synchronized String getLastServerResponse() {
			return lastServerResponse;
		}
	}

	public static MailerRegularBuilderImpl createFullyConfiguredMailerBuilder(final boolean authenticateProxy, final String prefix, @Nullable final TransportStrategy transportStrategy) {
		MailerRegularBuilderImpl mailerBuilder = MailerBuilder
				.withSMTPServer(prefix + "smtp host", 25, prefix + "username smtp", prefix + "password smtp")
				.verifyingServerIdentity(true)
				.withDebugLogging(true);

		if (transportStrategy != null) {
			mailerBuilder.withTransportStrategy(transportStrategy);
		}

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
				.withProperty("extra2", prefix + "value2");
	}
}
