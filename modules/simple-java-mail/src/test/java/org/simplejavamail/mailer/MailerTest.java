package org.simplejavamail.mailer;

import jakarta.mail.Session;
import jakarta.mail.internet.MimeMessage;
import org.jetbrains.annotations.Nullable;
import org.junit.Before;
import org.junit.Test;
import org.simplejavamail.api.email.Email;
import org.simplejavamail.api.email.EmailPopulatingBuilder;
import org.simplejavamail.api.mailer.CustomMailer;
import org.simplejavamail.api.mailer.Mailer;
import org.simplejavamail.api.mailer.config.OperationalConfig;
import org.simplejavamail.api.mailer.config.TransportStrategy;
import org.simplejavamail.config.ConfigLoader;
import org.simplejavamail.converter.EmailConverter;
import org.simplejavamail.converter.internal.mimemessage.ImmutableDelegatingSMTPMessage;
import org.simplejavamail.mailer.internal.MailerRegularBuilderImpl;
import org.simplejavamail.mailer.internal.SessionBasedEmailToMimeMessageConverter;
import org.simplejavamail.util.TestDataHelper;
import org.simplejavamail.utils.mail.dkim.DkimMessage;
import testutil.ConfigLoaderTestHelper;
import testutil.EmailHelper;

import javax.net.ssl.SSLSocketFactory;
import java.io.ByteArrayInputStream;
import java.io.File;
import java.io.IOException;
import java.util.AbstractMap.SimpleEntry;
import java.util.GregorianCalendar;
import java.util.Properties;
import java.util.UUID;

import static demo.ResourceFolderHelper.determineResourceFolder;
import static jakarta.xml.bind.DatatypeConverter.parseBase64Binary;
import static java.util.Calendar.APRIL;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.simplejavamail.api.mailer.config.TransportStrategy.SMTPS;
import static org.simplejavamail.api.mailer.config.TransportStrategy.SMTP_TLS;
import static org.simplejavamail.config.ConfigLoader.Property.OPPORTUNISTIC_TLS;

@SuppressWarnings("unused")
public class MailerTest {

	private static final String RESOURCES_PKCS = determineResourceFolder("simple-java-mail") + "/test/resources/pkcs12";

	@Before
	public void restoreOriginalStaticProperties() {
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
		assertThat(session.getProperty("mail.smtp.host")).isEqualTo("host");
		assertThat(session.getProperty("mail.smtp.port")).isEqualTo("25");
		assertThat(session.getProperty("mail.transport.protocol")).isEqualTo("smtp");
		
		assertThat(session.getProperty("mail.smtp.starttls.enable")).isEqualTo("true");
		assertThat(session.getProperty("mail.smtp.starttls.required")).isEqualTo("false");
		assertThat(session.getProperty("mail.smtp.ssl.trust")).isEqualTo("*");
		assertThat(session.getProperty("mail.smtp.ssl.checkserveridentity")).isNull();
		
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
		assertThat(session.getProperty("mail.smtp.ssl.checkserveridentity")).isNull();
		
		assertThat(session.getProperty("mail.smtp.user")).isEqualTo("username smtp");
		assertThat(session.getProperty("mail.smtp.auth")).isEqualTo("true");
		// the following two are because authentication is needed, otherwise proxy would be straightworward
		assertThat(session.getProperty("mail.smtp.socks.host")).isEqualTo("localhost");
		assertThat(session.getProperty("mail.smtp.socks.port")).isEqualTo("1081");
		assertThat(session.getProperty("extra-properties-property1")).isNull();
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
		assertThat(session.getProperty("mail.smtp.ssl.checkserveridentity")).isNull();
		
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

		emailPopulatingBuilder.signWithDomainKey(new ByteArrayInputStream(parseBase64Binary(privateDERkeyBase64)), "somemail.com", "select");
		MimeMessage mimeMessage = EmailConverter.emailToMimeMessage(emailPopulatingBuilder.buildEmail());
		// success, signing did not produce an error
		assertThat(mimeMessage).isInstanceOf(ImmutableDelegatingSMTPMessage.class);
		assertThat(((ImmutableDelegatingSMTPMessage) mimeMessage).getDelegate()).isInstanceOf(DkimMessage.class);
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

		emailPopulatingBuilder.signWithDomainKey(new ByteArrayInputStream(parseBase64Binary(privateDERkeyBase64)), "somemail.com", "select");
		emailPopulatingBuilder.signWithSmime(new File(RESOURCES_PKCS + "/smime_keystore.pkcs12"), "letmein", "smime_test_user_alias", "letmein");
		emailPopulatingBuilder.encryptWithSmime(new File(RESOURCES_PKCS + "/smime_test_user.pem.standard.crt"));

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
		
		TestDataHelper.fixDresscodeAttachment(emailFromMimeMessage);

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
