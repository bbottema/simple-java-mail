package org.simplejavamail.email.internal;

import org.apache.commons.collections4.map.HashedMap;
import org.junit.Test;
import org.simplejavamail.api.email.Email;
import org.simplejavamail.api.email.EmailAssert;
import org.simplejavamail.api.email.Recipient;
import org.simplejavamail.api.mailer.config.Pkcs12Config;
import org.simplejavamail.config.ConfigLoader.Property;
import org.simplejavamail.email.EmailBuilder;
import testutil.ConfigLoaderTestHelper;

import java.util.Map;

import static javax.mail.Message.RecipientType.BCC;
import static javax.mail.Message.RecipientType.CC;
import static javax.mail.Message.RecipientType.TO;
import static org.assertj.core.api.Assertions.assertThat;
import static org.simplejavamail.config.ConfigLoader.Property.DEFAULT_BCC_ADDRESS;
import static org.simplejavamail.config.ConfigLoader.Property.DEFAULT_BCC_NAME;
import static org.simplejavamail.config.ConfigLoader.Property.DEFAULT_BOUNCETO_ADDRESS;
import static org.simplejavamail.config.ConfigLoader.Property.DEFAULT_BOUNCETO_NAME;
import static org.simplejavamail.config.ConfigLoader.Property.DEFAULT_CC_ADDRESS;
import static org.simplejavamail.config.ConfigLoader.Property.DEFAULT_CC_NAME;
import static org.simplejavamail.config.ConfigLoader.Property.DEFAULT_FROM_ADDRESS;
import static org.simplejavamail.config.ConfigLoader.Property.DEFAULT_FROM_NAME;
import static org.simplejavamail.config.ConfigLoader.Property.DEFAULT_REPLYTO_ADDRESS;
import static org.simplejavamail.config.ConfigLoader.Property.DEFAULT_REPLYTO_NAME;
import static org.simplejavamail.config.ConfigLoader.Property.DEFAULT_SUBJECT;
import static org.simplejavamail.config.ConfigLoader.Property.DEFAULT_TO_ADDRESS;
import static org.simplejavamail.config.ConfigLoader.Property.DEFAULT_TO_NAME;
import static org.simplejavamail.config.ConfigLoader.Property.SMIME_ENCRYPTION_CERTIFICATE;
import static org.simplejavamail.config.ConfigLoader.Property.SMIME_SIGNING_KEYSTORE;
import static org.simplejavamail.config.ConfigLoader.Property.SMIME_SIGNING_KEYSTORE_PASSWORD;
import static org.simplejavamail.config.ConfigLoader.Property.SMIME_SIGNING_KEY_ALIAS;
import static org.simplejavamail.config.ConfigLoader.Property.SMIME_SIGNING_KEY_PASSWORD;

public class EmailPopulatingBuilderImpl2Test {

	@Test
	public void testConstructorApplyingPreconfiguredDefaults1() throws Exception {
		Map<Property, Object> value = new HashedMap<>();
		value.put(DEFAULT_FROM_ADDRESS, "test_from@domain.com");
		value.put(DEFAULT_FROM_NAME, "Test From");
		value.put(DEFAULT_REPLYTO_ADDRESS, "test_replyto@domain.com");
		value.put(DEFAULT_REPLYTO_NAME, "Test Replyto");
		value.put(DEFAULT_BOUNCETO_ADDRESS, "test_boundeto@domain.com");
		value.put(DEFAULT_BOUNCETO_NAME, "Test Bounceto");
		value.put(DEFAULT_TO_ADDRESS, "test_to1@domain.com,test_to2@domain.com");
		value.put(DEFAULT_TO_NAME, "test TO name");
		value.put(DEFAULT_CC_ADDRESS, "test_cc1@domain.com,test_cc2@domain.com");
		value.put(DEFAULT_CC_NAME, "test CC name");
		value.put(DEFAULT_BCC_ADDRESS, "test_bcc1@domain.com,test_bcc2@domain.com");
		value.put(DEFAULT_BCC_NAME, "test BCC name");
		value.put(DEFAULT_SUBJECT, "test subject");
		value.put(SMIME_SIGNING_KEYSTORE, "src/test/resources/pkcs12/smime_keystore.pkcs12");
		value.put(SMIME_SIGNING_KEYSTORE_PASSWORD, "letmein");
		value.put(SMIME_SIGNING_KEY_ALIAS, "smime_test_user_alias");
		value.put(SMIME_SIGNING_KEY_PASSWORD, "letmein");

		ConfigLoaderTestHelper.setResolvedProperties(value);

		EmailAssert.assertThat(EmailBuilder.startingBlank().buildEmail())
				.hasFromRecipient(new Recipient("Test From", "test_from@domain.com", null))
				.hasReplyToRecipient(new Recipient("Test Replyto", "test_replyto@domain.com", null))
				.hasBounceToRecipient(new Recipient("Test Bounceto", "test_boundeto@domain.com", null))
				.hasRecipients(
						new Recipient("test TO name", "test_to1@domain.com", TO), new Recipient("test TO name", "test_to2@domain.com", TO),
						new Recipient("test CC name", "test_cc1@domain.com", CC), new Recipient("test CC name", "test_cc2@domain.com", CC),
						new Recipient("test BCC name", "test_bcc1@domain.com", BCC), new Recipient("test BCC name", "test_bcc2@domain.com", BCC)
				)
				.hasSubject("test subject")
				.hasPkcs12ConfigForSmimeSigning(Pkcs12Config.builder()
						.pkcs12Store("src/test/resources/pkcs12/smime_keystore.pkcs12")
						.storePassword("letmein")
						.keyAlias("smime_test_user_alias")
						.keyPassword("letmein").build());
	}

	@Test
	public void testConstructorApplyingPreconfiguredDefaults2() throws Exception {
		HashedMap<Property, Object> value = new HashedMap<>();
		value.put(DEFAULT_FROM_ADDRESS, "test_from@domain.com");
		value.put(DEFAULT_REPLYTO_ADDRESS, "test_replyto@domain.com");
		value.put(DEFAULT_BOUNCETO_ADDRESS, "test_boundeto@domain.com");
		value.put(DEFAULT_TO_ADDRESS, "test_to1@domain.com,test_to2@domain.com");
		value.put(DEFAULT_CC_ADDRESS, "test_cc1@domain.com,test_cc2@domain.com");
		value.put(DEFAULT_BCC_ADDRESS, "test_bcc1@domain.com,test_bcc2@domain.com");
		value.put(SMIME_ENCRYPTION_CERTIFICATE, "src/test/resources/pkcs12/smime_test_user.pem.standard.crt");

		ConfigLoaderTestHelper.setResolvedProperties(value);

		Email email = EmailBuilder.startingBlank().buildEmail();
		EmailAssert.assertThat(email)
				.hasFromRecipient(new Recipient(null, "test_from@domain.com", null))
				.hasReplyToRecipient(new Recipient(null, "test_replyto@domain.com", null))
				.hasBounceToRecipient(new Recipient(null, "test_boundeto@domain.com", null))
				.hasRecipients(
						new Recipient(null, "test_to1@domain.com", TO), new Recipient(null, "test_to2@domain.com", TO),
						new Recipient(null, "test_cc1@domain.com", CC), new Recipient(null, "test_cc2@domain.com", CC),
						new Recipient(null, "test_bcc1@domain.com", BCC), new Recipient(null, "test_bcc2@domain.com", BCC)
				);

		assertThat(email.getX509CertificateForSmimeEncryption()).isNotNull();
	}
}