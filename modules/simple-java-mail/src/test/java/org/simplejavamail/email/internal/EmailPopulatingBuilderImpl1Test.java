package org.simplejavamail.email.internal;

import org.assertj.core.api.ThrowableAssert;
import org.bouncycastle.jce.provider.BouncyCastleProvider;
import org.bouncycastle.operator.OperatorCreationException;
import org.jetbrains.annotations.Nullable;
import org.junit.Before;
import org.junit.Test;
import org.simplejavamail.api.email.AttachmentResource;
import org.simplejavamail.api.email.Email;
import org.simplejavamail.api.email.EmailAssert;
import org.simplejavamail.api.email.EmailPopulatingBuilder;
import org.simplejavamail.api.email.Recipient;
import org.simplejavamail.email.EmailBuilder;
import org.simplejavamail.internal.util.CertificationUtil;
import testutil.ConfigLoaderTestHelper;
import testutil.EmailHelper;

import javax.activation.DataSource;
import javax.mail.Message;
import javax.mail.internet.InternetAddress;
import javax.mail.util.ByteArrayDataSource;
import java.io.ByteArrayInputStream;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.UnsupportedEncodingException;
import java.net.HttpURLConnection;
import java.net.URL;
import java.security.Security;
import java.security.cert.X509Certificate;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static demo.ResourceFolderHelper.determineResourceFolder;
import static javax.mail.Message.RecipientType.BCC;
import static javax.mail.Message.RecipientType.CC;
import static javax.mail.Message.RecipientType.TO;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.failBecauseExceptionWasNotThrown;
import static org.assertj.core.api.Assumptions.assumeThat;
import static org.mockito.Mockito.mock;
import static org.simplejavamail.util.TestDataHelper.getUrl;
import static org.simplejavamail.util.TestDataHelper.loadPkcs12KeyStore;
import static testutil.CertificationUtil.extractSignedBy;

public class EmailPopulatingBuilderImpl1Test {

	private static final String RESOURCES_PATH = determineResourceFolder("simple-java-mail") + "/test/resources";
	private static final String RESOURCES_PKCS = RESOURCES_PATH + "/pkcs12";

	private EmailPopulatingBuilder builder;

	@Before
	public void setup() {
		ConfigLoaderTestHelper.clearConfigProperties();
		builder = EmailBuilder.startingBlank();
	}

	@Test
	public void testBuilderFromAddress() {
		final Email email = builder
				.from(new Recipient("lollypop", "lol.pop@somemail.com", null))
				.buildEmail();

		assertThat(email.getFromRecipient().getName()).isEqualTo("lollypop");
		assertThat(email.getFromRecipient().getAddress()).isEqualTo("lol.pop@somemail.com");
		assertThat(email.getFromRecipient().getType()).isNull();
	}

	@Test
	public void testBuilderFromAddressOverwriteWithAlternativeBuilderMethod() {
		final Email email = builder
				.from("lollypop", "lol.pop@somemail.com") // should be overwritted
				.from(new Recipient("lollypop2", "lol.pop2@somemail.com", null))
				.buildEmail();

		assertThat(email.getFromRecipient().getName()).isEqualTo("lollypop2");
		assertThat(email.getFromRecipient().getAddress()).isEqualTo("lol.pop2@somemail.com");
		assertThat(email.getFromRecipient().getType()).isNull();
	}

	@Test
	public void testBuilderReplyToAddress() {
		final Email email = builder
				.withReplyTo(new Recipient("lollypop", "lol.pop@somemail.com", null))
				.buildEmail();

		assertThat(email.getReplyToRecipient().getName()).isEqualTo("lollypop");
		assertThat(email.getReplyToRecipient().getAddress()).isEqualTo("lol.pop@somemail.com");
		assertThat(email.getReplyToRecipient().getType()).isNull();
	}

	@Test
	public void testBuilderBounceToAddress() {
		final Email email = builder
				.withBounceTo(new Recipient("lollypop", "lol.pop@somemail.com", null))
				.buildEmail();

		assertThat(email.getBounceToRecipient().getName()).isEqualTo("lollypop");
		assertThat(email.getBounceToRecipient().getAddress()).isEqualTo("lol.pop@somemail.com");
		assertThat(email.getBounceToRecipient().getType()).isNull();
	}

	@Test
	public void testBuilderReturnReceiptToAddressNative() throws UnsupportedEncodingException {
		final Email email = builder
				.withReturnReceiptTo(new InternetAddress("lol.pop@somemail.com", "moo"))
				.buildEmail();

		assertThat(email.getReturnReceiptTo().getName()).isEqualTo("moo");
		assertThat(email.getReturnReceiptTo().getAddress()).isEqualTo("lol.pop@somemail.com");
		assertThat(email.getReturnReceiptTo().getType()).isNull();
	}

	@Test
	public void testBuilderReturnReceiptToAddress() {
		final Email email = builder
				.withReturnReceiptTo(new Recipient("lollypop", "lol.pop@somemail.com", null))
				.buildEmail();

		assertThat(email.getReturnReceiptTo().getName()).isEqualTo("lollypop");
		assertThat(email.getReturnReceiptTo().getAddress()).isEqualTo("lol.pop@somemail.com");
		assertThat(email.getReturnReceiptTo().getType()).isNull();
	}

	@Test
	public void testBuilderReturnReceiptToAddressWithFixedName() {
		final Email email = builder
				.withReturnReceiptTo("lollypop", "lol.pop@somemail.com")
				.buildEmail();

		assertThat(email.getReturnReceiptTo().getName()).isEqualTo("lollypop");
		assertThat(email.getReturnReceiptTo().getAddress()).isEqualTo("lol.pop@somemail.com");
		assertThat(email.getReturnReceiptTo().getType()).isNull();
	}

	@Test
	public void testBuilderReturnReceiptToAddressWithFixedNameOverridingTheOneFromAddress() throws UnsupportedEncodingException {
		final Email email = builder
				.withReturnReceiptTo("lollypop", new InternetAddress("lol.pop@somemail.com", "moo"))
				.buildEmail();

		assertThat(email.getReturnReceiptTo().getName()).isEqualTo("lollypop");
		assertThat(email.getReturnReceiptTo().getAddress()).isEqualTo("lol.pop@somemail.com");
		assertThat(email.getReturnReceiptTo().getType()).isNull();
	}

	@Test
	public void testBuilderReplyToAddressOverwriteWithAlternativeBuilderMethod() {
		final Email email = builder
				.withReplyTo("lollypop", "lol.pop@somemail.com") // should be overwritted
				.withReplyTo(new Recipient("lollypop2", "lol.pop2@somemail.com", null))
				.buildEmail();

		assertThat(email.getReplyToRecipient().getName()).isEqualTo("lollypop2");
		assertThat(email.getReplyToRecipient().getAddress()).isEqualTo("lol.pop2@somemail.com");
		assertThat(email.getReplyToRecipient().getType()).isNull();
	}

	@Test
	public void testBuilderBounceToAddressOverwriteWithAlternativeBuilderMethod() {
		final Email email = builder
				.withBounceTo("lollypop", "lol.pop@somemail.com") // should be overwritted
				.withBounceTo(new Recipient("lollypop2", "lol.pop2@somemail.com", null))
				.buildEmail();

		assertThat(email.getBounceToRecipient().getName()).isEqualTo("lollypop2");
		assertThat(email.getBounceToRecipient().getAddress()).isEqualTo("lol.pop2@somemail.com");
		assertThat(email.getBounceToRecipient().getType()).isNull();
	}

	@Test
	public void testBuilderReturnReceiptToAddressOverwriteWithAlternativeBuilderMethod() {
		final Email email = builder
				.withReturnReceiptTo("lollypop", "lol.pop@somemail.com") // should be overwritted
				.withReturnReceiptTo(new Recipient("lollypop2", "lol.pop2@somemail.com", null))
				.buildEmail();

		assertThat(email.getReturnReceiptTo().getName()).isEqualTo("lollypop2");
		assertThat(email.getReturnReceiptTo().getAddress()).isEqualTo("lol.pop2@somemail.com");
		assertThat(email.getReturnReceiptTo().getType()).isNull();
	}

	@Test
	public void testBuilderToAddresses() {
		final Email email = builder
				.to("1", "1@candyshop.org")
				.to(null, "2@candyshop.org")
				.to(new Recipient("3", "3@candyshop.org", null))
				.to(new Recipient(null, "4@candyshop.org", null))
				.to("5@candyshop.org")
				.to("6@candyshop.org,7@candyshop.org")
				.to("8@candyshop.org;9@candyshop.org")
				.to("10@candyshop.org;11@candyshop.org,12@candyshop.org")
				.to(new Recipient("13", "13@candyshop.org", null), new Recipient("14", "14@candyshop.org", null))
				.to("15", "15a@candyshop.org,15b@candyshop.org")
				.to("16", "16a@candyshop.org;16b@candyshop.org")
				.buildEmail();

		assertThat(email.getRecipients()).containsExactlyInAnyOrder(
				createRecipient("1", "1@candyshop.org", Message.RecipientType.TO),
				createRecipient(null, "2@candyshop.org", Message.RecipientType.TO),
				createRecipient("3", "3@candyshop.org", Message.RecipientType.TO),
				createRecipient(null, "4@candyshop.org", Message.RecipientType.TO),
				createRecipient(null, "5@candyshop.org", Message.RecipientType.TO),
				createRecipient(null, "6@candyshop.org", Message.RecipientType.TO),
				createRecipient(null, "7@candyshop.org", Message.RecipientType.TO),
				createRecipient(null, "8@candyshop.org", Message.RecipientType.TO),
				createRecipient(null, "9@candyshop.org", Message.RecipientType.TO),
				createRecipient(null, "10@candyshop.org", Message.RecipientType.TO),
				createRecipient(null, "11@candyshop.org", Message.RecipientType.TO),
				createRecipient(null, "12@candyshop.org", Message.RecipientType.TO),
				createRecipient("13", "13@candyshop.org", Message.RecipientType.TO),
				createRecipient("14", "14@candyshop.org", Message.RecipientType.TO),
				createRecipient("15", "15a@candyshop.org", Message.RecipientType.TO),
				createRecipient("15", "15b@candyshop.org", Message.RecipientType.TO),
				createRecipient("16", "16a@candyshop.org", Message.RecipientType.TO),
				createRecipient("16", "16b@candyshop.org", Message.RecipientType.TO)
		);
	}

	@Test
	public void testBuilderToAddresses_ComplexFormat() {
		final Email email = builder
				.toWithDefaultName("1", "1b <1@candyshop.org>")
				.to("5b <5@candyshop.org>")
				.to("6@candyshop.org,7b <7@candyshop.org>")
				.to("8b <8@candyshop.org>;9@candyshop.org")
				.to("10b <10@candyshop.org>;11b <11@candyshop.org>,12@candyshop.org")
				.buildEmail();

		assertThat(email.getRecipients()).containsExactlyInAnyOrder(
				createRecipient("1b", "1@candyshop.org", Message.RecipientType.TO),
				createRecipient("5b", "5@candyshop.org", Message.RecipientType.TO),
				createRecipient(null, "6@candyshop.org", Message.RecipientType.TO),
				createRecipient("7b", "7@candyshop.org", Message.RecipientType.TO),
				createRecipient("8b", "8@candyshop.org", Message.RecipientType.TO),
				createRecipient(null, "9@candyshop.org", Message.RecipientType.TO),
				createRecipient("10b", "10@candyshop.org", Message.RecipientType.TO),
				createRecipient("11b", "11@candyshop.org", Message.RecipientType.TO),
				createRecipient(null, "12@candyshop.org", Message.RecipientType.TO)
		);
	}

	@Test
	public void testBuilderCCAddresses() {
		final Email email = builder
				.cc("1", "1@candyshop.org")
				.cc(null, "2@candyshop.org")
				.cc(new Recipient("3", "3@candyshop.org", null))
				.cc(new Recipient(null, "4@candyshop.org", null))
				.cc("5@candyshop.org")
				.cc("6@candyshop.org,7@candyshop.org")
				.cc("8@candyshop.org;9@candyshop.org")
				.cc("10@candyshop.org;11@candyshop.org,12@candyshop.org")
				.cc(new Recipient("13", "13@candyshop.org", null), new Recipient("14", "14@candyshop.org", null))
				.cc("15", "15a@candyshop.org,15b@candyshop.org")
				.cc("16", "16a@candyshop.org;16b@candyshop.org")
				.buildEmail();

		assertThat(email.getRecipients()).containsExactlyInAnyOrder(
				createRecipient("1", "1@candyshop.org", Message.RecipientType.CC),
				createRecipient(null, "2@candyshop.org", Message.RecipientType.CC),
				createRecipient("3", "3@candyshop.org", Message.RecipientType.CC),
				createRecipient(null, "4@candyshop.org", Message.RecipientType.CC),
				createRecipient(null, "5@candyshop.org", Message.RecipientType.CC),
				createRecipient(null, "6@candyshop.org", Message.RecipientType.CC),
				createRecipient(null, "7@candyshop.org", Message.RecipientType.CC),
				createRecipient(null, "8@candyshop.org", Message.RecipientType.CC),
				createRecipient(null, "9@candyshop.org", Message.RecipientType.CC),
				createRecipient(null, "10@candyshop.org", Message.RecipientType.CC),
				createRecipient(null, "11@candyshop.org", Message.RecipientType.CC),
				createRecipient(null, "12@candyshop.org", Message.RecipientType.CC),
				createRecipient("13", "13@candyshop.org", Message.RecipientType.CC),
				createRecipient("14", "14@candyshop.org", Message.RecipientType.CC),
				createRecipient("15", "15a@candyshop.org", Message.RecipientType.CC),
				createRecipient("15", "15b@candyshop.org", Message.RecipientType.CC),
				createRecipient("16", "16a@candyshop.org", Message.RecipientType.CC),
				createRecipient("16", "16b@candyshop.org", Message.RecipientType.CC)
		);
	}

	@Test
	public void testBuilderCCAddresses_ComplexFormat() {
		final Email email = builder
				.ccWithDefaultName("1", "1b <1@candyshop.org>")
				.cc("5b <5@candyshop.org>")
				.cc("6@candyshop.org,7b <7@candyshop.org>")
				.cc("8b <8@candyshop.org>;9@candyshop.org")
				.cc("10b <10@candyshop.org>;11b <11@candyshop.org>,12@candyshop.org")
				.buildEmail();

		assertThat(email.getRecipients()).containsExactlyInAnyOrder(
				createRecipient("1b", "1@candyshop.org", Message.RecipientType.CC),
				createRecipient("5b", "5@candyshop.org", Message.RecipientType.CC),
				createRecipient(null, "6@candyshop.org", Message.RecipientType.CC),
				createRecipient("7b", "7@candyshop.org", Message.RecipientType.CC),
				createRecipient("8b", "8@candyshop.org", Message.RecipientType.CC),
				createRecipient(null, "9@candyshop.org", Message.RecipientType.CC),
				createRecipient("10b", "10@candyshop.org", Message.RecipientType.CC),
				createRecipient("11b", "11@candyshop.org", Message.RecipientType.CC),
				createRecipient(null, "12@candyshop.org", Message.RecipientType.CC)
		);
	}

	@Test
	public void testBuilderBCCAddresses() {
		final Email email = builder
				.bcc("1", "1@candyshop.org")
				.bcc(null, "2@candyshop.org")
				.bcc(new Recipient("3", "3@candyshop.org", null))
				.bcc(new Recipient(null, "4@candyshop.org", null))
				.bcc("5@candyshop.org")
				.bcc("6@candyshop.org,7@candyshop.org")
				.bcc("8@candyshop.org;9@candyshop.org")
				.bcc("10@candyshop.org;11@candyshop.org,12@candyshop.org")
				.bcc(new Recipient("13", "13@candyshop.org", null), new Recipient("14", "14@candyshop.org", null))
				.bcc("15", "15a@candyshop.org,15b@candyshop.org")
				.bcc("16", "16a@candyshop.org;16b@candyshop.org")
				.buildEmail();

		assertThat(email.getRecipients()).containsExactlyInAnyOrder(
				createRecipient("1", "1@candyshop.org", Message.RecipientType.BCC),
				createRecipient(null, "2@candyshop.org", Message.RecipientType.BCC),
				createRecipient("3", "3@candyshop.org", Message.RecipientType.BCC),
				createRecipient(null, "4@candyshop.org", Message.RecipientType.BCC),
				createRecipient(null, "5@candyshop.org", Message.RecipientType.BCC),
				createRecipient(null, "6@candyshop.org", Message.RecipientType.BCC),
				createRecipient(null, "7@candyshop.org", Message.RecipientType.BCC),
				createRecipient(null, "8@candyshop.org", Message.RecipientType.BCC),
				createRecipient(null, "9@candyshop.org", Message.RecipientType.BCC),
				createRecipient(null, "10@candyshop.org", Message.RecipientType.BCC),
				createRecipient(null, "11@candyshop.org", Message.RecipientType.BCC),
				createRecipient(null, "12@candyshop.org", Message.RecipientType.BCC),
				createRecipient("13", "13@candyshop.org", Message.RecipientType.BCC),
				createRecipient("14", "14@candyshop.org", Message.RecipientType.BCC),
				createRecipient("15", "15a@candyshop.org", Message.RecipientType.BCC),
				createRecipient("15", "15b@candyshop.org", Message.RecipientType.BCC),
				createRecipient("16", "16a@candyshop.org", Message.RecipientType.BCC),
				createRecipient("16", "16b@candyshop.org", Message.RecipientType.BCC)
		);
	}

	@Test
	public void testBuilderBCCAddresses_ComplexFormat() {
		final Email email = builder
				.bccWithDefaultName("1", "1b <1@candyshop.org>")
				.bcc("5b <5@candyshop.org>")
				.bcc("6@candyshop.org,7b <7@candyshop.org>")
				.bcc("8b <8@candyshop.org>;9@candyshop.org")
				.bcc("10b <10@candyshop.org>;11b <11@candyshop.org>,12@candyshop.org")
				.buildEmail();

		assertThat(email.getRecipients()).containsExactlyInAnyOrder(
				createRecipient("1b", "1@candyshop.org", Message.RecipientType.BCC),
				createRecipient("5b", "5@candyshop.org", Message.RecipientType.BCC),
				createRecipient(null, "6@candyshop.org", Message.RecipientType.BCC),
				createRecipient("7b", "7@candyshop.org", Message.RecipientType.BCC),
				createRecipient("8b", "8@candyshop.org", Message.RecipientType.BCC),
				createRecipient(null, "9@candyshop.org", Message.RecipientType.BCC),
				createRecipient("10b", "10@candyshop.org", Message.RecipientType.BCC),
				createRecipient("11b", "11@candyshop.org", Message.RecipientType.BCC),
				createRecipient(null, "12@candyshop.org", Message.RecipientType.BCC)
		);
	}

	@Test
	public void testBuilderNotificationFlags_DefaultOff() {
		final Email email = builder
				.withReplyTo("replyTo", "1@candyshop.org")
				.from("from", "2@candyshop.org")
				.buildEmail();

		assertThat(email.isUseDispositionNotificationTo()).isFalse();
		assertThat(email.isUseReturnReceiptTo()).isFalse();
		assertThat(email.getDispositionNotificationTo()).isNull();
		assertThat(email.getReturnReceiptTo()).isNull();
	}

	@Test
	public void testBuilderNotificationFlags_ReDefaultToReplyTo() {
		final Email email = builder
				.withReplyTo("replyTo", "1@candyshop.org")
				.from("from", "2@candyshop.org")
				.withDispositionNotificationTo("custom@candyshop.com")
				.withReturnReceiptTo("custom@candyshop.com")
				.withDispositionNotificationTo()
				.withReturnReceiptTo()
				.buildEmail();

		assertThat(email.isUseDispositionNotificationTo()).isTrue();
		assertThat(email.isUseReturnReceiptTo()).isTrue();
		assertThat(email.getDispositionNotificationTo()).isEqualTo(new Recipient("replyTo", "1@candyshop.org", null));
		assertThat(email.getReturnReceiptTo()).isEqualTo(new Recipient("replyTo", "1@candyshop.org", null));
	}

	@Test
	public void testBuilderNotificationFlags_DefaultToReplyTo() {
		final Email email = builder
				.withReplyTo("replyTo", "1@candyshop.org")
				.from("from", "2@candyshop.org")
				.withDispositionNotificationTo()
				.withReturnReceiptTo()
				.buildEmail();

		assertThat(email.isUseDispositionNotificationTo()).isTrue();
		assertThat(email.isUseReturnReceiptTo()).isTrue();
		assertThat(email.getDispositionNotificationTo()).isEqualTo(new Recipient("replyTo", "1@candyshop.org", null));
		assertThat(email.getReturnReceiptTo()).isEqualTo(new Recipient("replyTo", "1@candyshop.org", null));
	}

	@Test
	public void testBuilderNotificationFlags_CustomAddress() {
		final Email email = builder
				.withReplyTo("replyTo", "1@candyshop.org")
				.from("from", "2@candyshop.org")
				.withDispositionNotificationTo("customa@candyshop.org")
				.withReturnReceiptTo("customb@candyshop.org")
				.buildEmail();

		assertThat(email.isUseDispositionNotificationTo()).isTrue();
		assertThat(email.isUseReturnReceiptTo()).isTrue();
		assertThat(email.getDispositionNotificationTo()).isEqualTo(new Recipient(null, "customa@candyshop.org", null));
		assertThat(email.getReturnReceiptTo()).isEqualTo(new Recipient(null, "customb@candyshop.org", null));
	}

	@Test
	public void testBuilderEmbeddingImages_UnhappyScenario() {
		builder
				.withEmbeddedImage("a", new ByteArrayDataSource(new byte[3], ""))
				.withEmbeddedImage(null, new DataSourceWithDummyName())
				.withEmbeddedImage("a", new byte[3], "mimetype");
		try {
			builder.withEmbeddedImage(null, new ByteArrayDataSource(new byte[3], ""));
			failBecauseExceptionWasNotThrown(EmailException.class);
		} catch (EmailException e) {
			// ok
		}
		try {
			//noinspection ConstantConditions
			builder.withEmbeddedImage(null, new byte[3], "mimetype");
			failBecauseExceptionWasNotThrown(IllegalArgumentException.class);
		} catch (IllegalArgumentException e) {
			// ok
		}
	}

	@Test
	public void testBuilderAddingAttachmentsWithMissingNameWithoutExceptions() {
		builder
				.withAttachment("a", new ByteArrayDataSource(new byte[3], "text/txt"))
				.withAttachment(null, new DataSourceWithDummyName())
				.withAttachment("a", new byte[3], "text/txt")
				.withAttachment(null, new ByteArrayDataSource(new byte[3], "text/txt"))
				.withAttachment(null, new byte[3], "text/txt");
		// ok no exceptions
	}

	@Test
	public void testPrependText_ToEmptyText() {
		Email test = builder
				.prependText("test")
				.buildEmail();

		EmailAssert.assertThat(test).hasPlainText("test");
	}

	@Test
	public void testPrependText_ToNonEmptyText() {
		Email test = builder
				.withPlainText("moo")
				.withHTMLText("ignore")
				.prependText("test\n")
				.buildEmail();

		EmailAssert.assertThat(test).hasPlainText("test\nmoo");
	}

	@Test
	public void testPrependTextHTML_ToEmptyText() {
		Email test = builder
				.prependTextHTML("test")
				.buildEmail();

		EmailAssert.assertThat(test).hasHTMLText("test");
	}

	@Test
	public void testPrependTextHTML_ToNonEmptyText() {
		Email test = builder
				.withPlainText("ignore")
				.withHTMLText("moo")
				.prependTextHTML("test\n")
				.buildEmail();

		EmailAssert.assertThat(test).hasHTMLText("test\nmoo");
	}

	@Test
	public void testAppendText_ToEmptyText() {
		Email test = builder
				.appendText("test")
				.buildEmail();

		EmailAssert.assertThat(test).hasPlainText("test");
	}

	@Test
	public void testAppendText_ToNonEmptyText() {
		Email test = builder
				.withPlainText("moo")
				.withHTMLText("ignore")
				.appendText("\ntest")
				.buildEmail();

		EmailAssert.assertThat(test).hasPlainText("moo\ntest");
	}

	@Test
	public void testAppendTextHTML_ToEmptyText() {
		Email test = builder
				.appendTextHTML("test")
				.buildEmail();

		EmailAssert.assertThat(test).hasHTMLText("test");
	}

	@Test
	public void testAppendTextHTML_ToNonEmptyText() {
		Email test = builder
				.withPlainText("ignore")
				.withHTMLText("moo")
				.appendTextHTML("\ntest")
				.buildEmail();

		EmailAssert.assertThat(test).hasHTMLText("moo\ntest");
	}

	@Test
	public void testAddRecipients_Basic_Named() {
		builder.to("name1", "1@domain.com");
		builder.cc("name2", "2@domain.com,3@domain.com");
		builder.bcc("name3", "4@domain.com;5@domain.com");
		builder.to("name4", "6@domain.com;7@domain.com,8@domain.com");

		assertThat(builder.buildEmail().getRecipients()).containsExactlyInAnyOrder(
				new Recipient("name1", "1@domain.com", TO),
				new Recipient("name2", "2@domain.com", CC),
				new Recipient("name2", "3@domain.com", CC),
				new Recipient("name3", "4@domain.com", BCC),
				new Recipient("name3", "5@domain.com", BCC),
				new Recipient("name4", "6@domain.com", TO),
				new Recipient("name4", "7@domain.com", TO),
				new Recipient("name4", "8@domain.com", TO)
		);
	}

	@Test
	public void testAddRecipients_Complex_Named() {
		builder.toWithDefaultName("name1", "name1b <1@domain.com>");
		builder.ccWithDefaultName("name2", "name2b <2@domain.com>,3@domain.com");
		builder.bccWithDefaultName("name3", "4@domain.com;name3b <5@domain.com>");
		builder.toWithDefaultName("name4", "name4b <6@domain.com>;name5b <7@domain.com>,name6b <8@domain.com>");

		assertThat(builder.buildEmail().getRecipients()).containsExactlyInAnyOrder(
				new Recipient("name1b", "1@domain.com", TO),
				new Recipient("name2b", "2@domain.com", CC),
				new Recipient("name2", "3@domain.com", CC),
				new Recipient("name3", "4@domain.com", BCC),
				new Recipient("name3b", "5@domain.com", BCC),
				new Recipient("name4b", "6@domain.com", TO),
				new Recipient("name5b", "7@domain.com", TO),
				new Recipient("name6b", "8@domain.com", TO)
		);
	}

	@Test
	public void testAddRecipientsVarArgs_Basic_Named() {
		builder.to("name1", "1@domain.com");
		builder.cc("name2", "2@domain.com", "3@domain.com");

		assertThat(builder.buildEmail().getRecipients()).containsExactlyInAnyOrder(
				new Recipient("name1", "1@domain.com", TO),
				new Recipient("name2", "2@domain.com", CC),
				new Recipient("name2", "3@domain.com", CC)
		);
	}

	@Test
	public void testAddRecipients_DefaultToName_A() {
		builder.to(null, "to1@domain.com");
		builder.toWithDefaultName("to_default", "to included <to2@domain.com>");
		builder.to("to_default", "to3@domain.com");
		builder.toWithFixedName("to_fixed", "to included <to4@domain.com>");
		builder.to("to_fixed", "to included <to5@domain.com>");

		builder.cc(null, "cc1@domain.com");
		builder.ccWithDefaultName("cc_default", "cc included <cc2@domain.com>");
		builder.cc("cc_default", "cc3@domain.com");
		builder.ccWithFixedName("cc_fixed", "cc included <cc4@domain.com>");
		builder.cc("cc_fixed", "cc included <cc5@domain.com>");

		builder.bcc(null, "bcc1@domain.com");
		builder.bccWithDefaultName("bcc_default", "bcc included <bcc2@domain.com>");
		builder.bcc("bcc_default", "bcc3@domain.com");
		builder.bccWithFixedName("bcc_fixed", "bcc included <bcc4@domain.com>");
		builder.bcc("bcc_fixed", "bcc included <bcc5@domain.com>");

		assertThat(builder.buildEmail().getRecipients()).containsExactlyInAnyOrder(
				new Recipient(null, "to1@domain.com", TO),
				new Recipient("to included", "to2@domain.com", TO),
				new Recipient("to_default", "to3@domain.com", TO),
				new Recipient("to_fixed", "to4@domain.com", TO),
				new Recipient("to_fixed", "to5@domain.com", TO),

				new Recipient(null, "cc1@domain.com", CC),
				new Recipient("cc included", "cc2@domain.com", CC),
				new Recipient("cc_default", "cc3@domain.com", CC),
				new Recipient("cc_fixed", "cc4@domain.com", CC),
				new Recipient("cc_fixed", "cc5@domain.com", CC),

				new Recipient(null, "bcc1@domain.com", BCC),
				new Recipient("bcc included", "bcc2@domain.com", BCC),
				new Recipient("bcc_default", "bcc3@domain.com", BCC),
				new Recipient("bcc_fixed", "bcc4@domain.com", BCC),
				new Recipient("bcc_fixed", "bcc5@domain.com", BCC)
		);
	}

	@Test
	public void testAddRecipients_DefaultToName_MultipleAddress() {
		builder.toWithDefaultName("to_default", "to_included <1@domain.com>", "2@domain.com");
		builder.ccWithDefaultName("cc_default", "cc_included <3@domain.com>", "4@domain.com");
		builder.bccWithDefaultName("bcc_default", "bcc_included <5@domain.com>", "6@domain.com");

		assertThat(builder.buildEmail().getRecipients()).containsExactlyInAnyOrder(
				new Recipient("to_included", "1@domain.com", TO),
				new Recipient("to_default", "2@domain.com", TO),
				new Recipient("cc_included", "3@domain.com", CC),
				new Recipient("cc_default", "4@domain.com", CC),
				new Recipient("bcc_included", "5@domain.com", BCC),
				new Recipient("bcc_default", "6@domain.com", BCC)
		);
	}

	@Test
	public void testAddRecipientsVarArgs_Complex_Named() {
		builder.toWithDefaultName("name1", "name1b <1@domain.com>");
		builder.ccWithDefaultName("name2", "name2b <2@domain.com>", "name3b <3@domain.com>");

		assertThat(builder.getRecipients()).containsExactlyInAnyOrder(
				new Recipient("name1b", "1@domain.com", TO),
				new Recipient("name2b", "2@domain.com", CC),
				new Recipient("name3b", "3@domain.com", CC)
		);
	}

	@Test
	public void testAddRecipientsVarArgs_Basic_Nameless() {
		builder.to("1@domain.com");
		builder.ccMultiple("2@domain.com", "3@domain.com");

		assertThat(builder.getRecipients()).containsExactlyInAnyOrder(
				new Recipient(null, "1@domain.com", TO),
				new Recipient(null, "2@domain.com", CC),
				new Recipient(null, "3@domain.com", CC)
		);
	}

	@Test
	public void testAddRecipientsVarArgs_Complex_Nameless() {
		builder.to("name1b <1@domain.com>");
		builder.ccMultiple("name2b <2@domain.com>", "name3b <3@domain.com>");

		assertThat(builder.getRecipients()).containsExactlyInAnyOrder(
				new Recipient("name1b", "1@domain.com", TO),
				new Recipient("name2b", "2@domain.com", CC),
				new Recipient("name3b", "3@domain.com", CC)
		);
	}

	@Test
	public void testAddRecipients_Basic_Nameless() {
		builder.to("1@domain.com");
		builder.cc("2@domain.com,3@domain.com");
		builder.bcc("4@domain.com;5@domain.com");
		builder.to("6@domain.com;7@domain.com,8@domain.com");

		assertThat(builder.getRecipients()).containsExactlyInAnyOrder(
				new Recipient(null, "1@domain.com", TO),
				new Recipient(null, "2@domain.com", CC),
				new Recipient(null, "3@domain.com", CC),
				new Recipient(null, "4@domain.com", BCC),
				new Recipient(null, "5@domain.com", BCC),
				new Recipient(null, "6@domain.com", TO),
				new Recipient(null, "7@domain.com", TO),
				new Recipient(null, "8@domain.com", TO)
		);
	}

	@Test
	public void testAddRecipients_Complex_Nameless() {
		builder.to("name1b <1@domain.com>");
		builder.cc("name2b <2@domain.com>,3@domain.com");
		builder.bcc("4@domain.com;name3b <5@domain.com>");
		builder.to("name4b <6@domain.com>;name5b <7@domain.com>,name6b <8@domain.com>");

		assertThat(builder.getRecipients()).containsExactlyInAnyOrder(
				new Recipient("name1b", "1@domain.com", TO),
				new Recipient("name2b", "2@domain.com", CC),
				new Recipient(null, "3@domain.com", CC),
				new Recipient(null, "4@domain.com", BCC),
				new Recipient("name3b", "5@domain.com", BCC),
				new Recipient("name4b", "6@domain.com", TO),
				new Recipient("name5b", "7@domain.com", TO),
				new Recipient("name6b", "8@domain.com", TO)
		);
	}

	@Test
	public void testAddRecipients_Complex_Quicktest() {
		// accept valid addresses:
		builder.to("Abc\\@def@example.com");
		builder.to("Fred\\ Bloggs@example.com");
		builder.to("Joe.\\\\Blow@example.com");
		builder.to("\"Abc@def\"@example.com");
		builder.to("\"Fred Bloggs\"@example.com");
		builder.to("customer/department=shipping@example.com");
		builder.to("$A12345@example.com");
		builder.to("!def!xyz%abc@example.com");
		builder.to("_somename@example.com");
		builder.to("very.“():[]”.VERY.“very@\\\\ \"very”.unusual@strange.example.com");

		// even accept invalid addresses:
		builder.to("Name <1@domai@n.com>");

		// OK, InternetAddress#parse() didn't error out on these addresses
	}

	@Test
	public void testSignWithSmime_WithConfigObject() {
		builder.signWithSmime(loadPkcs12KeyStore());

		final Email email = builder.buildEmail();

		assertThat(email.getPkcs12ConfigForSmimeSigning()).isNotNull();
		assertThat(email.getPkcs12ConfigForSmimeSigning().getPkcs12StoreData()).isNotNull();
		assertThat(email.getPkcs12ConfigForSmimeSigning().getStorePassword()).isEqualTo("letmein".toCharArray());
		assertThat(email.getPkcs12ConfigForSmimeSigning().getKeyAlias()).isEqualTo("smime_test_user_alias");
		assertThat(email.getPkcs12ConfigForSmimeSigning().getKeyPassword()).isEqualTo("letmein".toCharArray());
	}

	@Test
	public void testSignWithSmime_WithConfigParameters() {
		builder.signWithSmime(new File(RESOURCES_PKCS + "/smime_keystore.pkcs12"), "letmein", "smime_test_user_alias", "letmein");

		final Email email = builder.buildEmail();

		assertThat(email.getPkcs12ConfigForSmimeSigning()).isNotNull();
		assertThat(email.getPkcs12ConfigForSmimeSigning().getPkcs12StoreData()).isNotNull();
		assertThat(email.getPkcs12ConfigForSmimeSigning().getStorePassword()).isEqualTo("letmein".toCharArray());
		assertThat(email.getPkcs12ConfigForSmimeSigning().getKeyAlias()).isEqualTo("smime_test_user_alias");
		assertThat(email.getPkcs12ConfigForSmimeSigning().getKeyPassword()).isEqualTo("letmein".toCharArray());
	}

	@Test
	public void testEncryptWithSmime_FromFile() throws Exception {
		Security.addProvider(new BouncyCastleProvider());

		builder.encryptWithSmime(new File(RESOURCES_PKCS + "/smime_test_user.pem.standard.crt"));

		final X509Certificate certificateOut = builder.buildEmail().getX509CertificateForSmimeEncryption();

		assertThat(certificateOut).isNotNull();
		assertSignedBy(certificateOut, "Benny Bottema");
	}

	@Test
	public void testEncryptWithSmime_FromFilePath() throws Exception {
		Security.addProvider(new BouncyCastleProvider());

		builder.encryptWithSmime(RESOURCES_PKCS + "/smime_test_user.pem.standard.crt");

		final X509Certificate certificateOut = builder.buildEmail().getX509CertificateForSmimeEncryption();

		assertThat(certificateOut).isNotNull();
		assertSignedBy(certificateOut, "Benny Bottema");
	}

	@Test
	public void testWithEmbeddedImages() throws IOException {
		List<AttachmentResource> embeddedImages = new ArrayList<>();
		embeddedImages.add(new AttachmentResource("attachment1", getDataSource("blahblah.txt")));
		embeddedImages.add(new AttachmentResource(null, getDataSource("blahblah.txt")));
		embeddedImages.add(new AttachmentResource("attachment1", new ByteArrayDataSource("", "text/text")));

		Email email = builder.withEmbeddedImages(embeddedImages).buildEmail();

		EmailAssert.assertThat(email).hasOnlyEmbeddedImages(embeddedImages);
	}

	@Test
	public void testContentMutation_Text() {
		Email email = setPlainText(builder).buildEmail();

		EmailAssert.assertThat(email).hasPlainText("<prepended2><main2><appended3>");
		EmailAssert.assertThat(email).hasHTMLText(null);
	}

	@Test
	public void testContentMutation_HTML() {
		Email email = setHTMLText(builder).buildEmail();

		EmailAssert.assertThat(email).hasPlainText(null);
		EmailAssert.assertThat(email).hasHTMLText("<prepended2><main2><appended3>");
	}

	@Test
	public void testContentMutation_BothTextAndHTML() {
		setPlainText(builder);
		setHTMLText(builder);

		Email email = builder.buildEmail();

		EmailAssert.assertThat(email).hasPlainText("<prepended2><main2><appended3>");
		EmailAssert.assertThat(email).hasHTMLText("<prepended2><main2><appended3>");
	}

	private EmailPopulatingBuilder setPlainText(EmailPopulatingBuilder builder) {
		return builder
				.appendText("<appended1>")
				.withPlainText("<main1>")
				.prependText("<prepended1>")
				.appendText("<appended2>")
				.withPlainText("<main2>")
				.prependText("<prepended2>")
				.appendText("<appended3>");
	}

	private EmailPopulatingBuilder setHTMLText(EmailPopulatingBuilder builder) {
		return builder
				.appendTextHTML("<appended1>")
				.withHTMLText("<main1>")
				.prependTextHTML("<prepended1>")
				.appendTextHTML("<appended2>")
				.withHTMLText("<main2>")
				.prependTextHTML("<prepended2>")
				.appendTextHTML("<appended3>");
	}

	@SuppressWarnings("SameParameterValue")
	private ByteArrayDataSource getDataSource(@Nullable String name)
			throws IOException {
		ByteArrayDataSource ds = new ByteArrayDataSource("", "text/text");
		ds.setName(name);
		return ds;
	}

	@Test
	public void testSignWithDomainKey() {
		final byte[] buf = {'1', '2', '3'};

		final Email email = builder
				.from("a.b.com")
				.signWithDomainKey(buf, "domain", "selector")
				.buildEmail();

		assertThat(Arrays.equals(email.getDkimPrivateKeyData(), buf)).isTrue();
		EmailAssert.assertThat(email).hasDkimSelector("selector");
		EmailAssert.assertThat(email).hasDkimSigningDomain("domain");
	}

	@Test
	public void testEmbeddingImagesWithDynamicDataSourceResolution_absoluteFilePath()
			throws IOException {
		final File file = new File(RESOURCES_PATH + "/log4j2.xml");

		final Email email = builder
				.withEmbeddedImageAutoResolutionForFiles(true)
				.withEmbeddedImageAutoResolutionForClassPathResources(true)
				.withEmbeddedImageAutoResolutionForURLs(true)
				.withHTMLText("<img src=\"cid:cid_name\"/>")
				.appendTextHTML("<img src=\"" + file.getAbsolutePath() + "\"/>")
				.buildEmail();

		verifyEmbeddedImage(email, "<Console name=\"console\" target=\"SYSTEM_OUT\">");
	}

	@Test
	public void testEmbeddingImagesWithDynamicDataSourceResolution_relativeFilePathWithNoBasedir() {
		final EmailPopulatingBuilder emailPopulatingBuilder = builder
				.withEmbeddedImageAutoResolutionForFiles(true)
				.withEmbeddedImageAutoResolutionForClassPathResources(true)
				.withEmbeddedImageAutoResolutionForURLs(true)
				.embeddedImageAutoResolutionMustBeSuccesful(true)
				.withHTMLText("<img src=\"cid:cid_name\"/>")
				.appendTextHTML("<img src=\"log4j2.xml\"/>");

		assertThatThrownBy(new ThrowableAssert.ThrowingCallable() {
			public void call() {
				emailPopulatingBuilder.buildEmail();
			}
		})
				.isInstanceOf(EmailException.class)
				.hasMessageContaining("Unable to dynamically resolve data source for the following image src: log4j2.xml");
	}

	@Test
	public void testEmbeddingImagesWithDynamicDataSourceResolution_relativeFilePathWithBasedir()
			throws IOException {
		final Email email = builder
				.withEmbeddedImageAutoResolutionForFiles(true)
				.withEmbeddedImageAutoResolutionForClassPathResources(true)
				.withEmbeddedImageAutoResolutionForURLs(true)
				.withEmbeddedImageBaseDir(RESOURCES_PATH)
				.withHTMLText("<img src=\"cid:cid_name\"/>")
				.appendTextHTML("<img src=\"pkcs12/about all this.txt\"/>")
				.buildEmail();

		verifyEmbeddedImage(email, "to generate CA cert, private key and and S/MIME format");
	}

	@Test
	public void testEmbeddingImagesWithDynamicDataSourceResolution_absoluteFilePathNestedUnderBasedir_AllowFlagTrue()
			throws IOException {
		final Email email = builder
				.withEmbeddedImageAutoResolutionForFiles(true)
				.withEmbeddedImageAutoResolutionForClassPathResources(true)
				.withEmbeddedImageAutoResolutionForURLs(true)
				.withEmbeddedImageBaseDir(RESOURCES_PATH)
				.allowingEmbeddedImageOutsideBaseDir(true)
				.withHTMLText("<img src=\"cid:cid_name\"/>")
				.appendTextHTML("<img src=\"" + RESOURCES_PATH + "/pkcs12/about all this.txt\"/>")
				.buildEmail();

		verifyEmbeddedImage(email, "to generate CA cert, private key and and S/MIME format");
	}

	@Test
	public void testEmbeddingImagesWithDynamicDataSourceResolution_absoluteFilePathNestedUnderBasedir_AllowFlagFalseSameAsTrue()
			throws IOException {
		final Email email = builder
				.withEmbeddedImageAutoResolutionForFiles(true)
				.withEmbeddedImageAutoResolutionForClassPathResources(true)
				.withEmbeddedImageAutoResolutionForURLs(true)
				.withEmbeddedImageBaseDir(RESOURCES_PATH)
				.allowingEmbeddedImageOutsideBaseDir(false)
				.withHTMLText("<img src=\"cid:cid_name\"/>")
				.appendTextHTML("<img src=\"" + RESOURCES_PATH + "/pkcs12/about all this.txt\"/>")
				.buildEmail();

		verifyEmbeddedImage(email, "to generate CA cert, private key and and S/MIME format");
	}

	@Test
	public void testEmbeddingImagesWithDynamicDataSourceResolution_absoluteFilePathOutsideBasedir_AllowedTrue()
			throws IOException {
		final Email email = builder
				.withEmbeddedImageAutoResolutionForFiles(true)
				.withEmbeddedImageAutoResolutionForClassPathResources(true)
				.withEmbeddedImageAutoResolutionForURLs(true)
				.withEmbeddedImageBaseDir(RESOURCES_PATH + "/pkcs12")
				.allowingEmbeddedImageOutsideBaseDir(true)
				.withHTMLText("<img src=\"cid:cid_name\"/>")
				.appendTextHTML("<img src=\"" + RESOURCES_PATH + "/log4j2.xml\"/>")
				.buildEmail();

		verifyEmbeddedImage(email, "<Console name=\"console\" target=\"SYSTEM_OUT\">");
	}

	@Test
	public void testEmbeddingImagesWithDynamicDataSourceResolution_absoluteFilePathOutsideBasedir_AllowedFalse() {
		final EmailPopulatingBuilder emailPopulatingBuilder = builder
				.withEmbeddedImageAutoResolutionForFiles(true)
				.withEmbeddedImageAutoResolutionForClassPathResources(true)
				.withEmbeddedImageAutoResolutionForURLs(true)
				.withEmbeddedImageBaseDir(RESOURCES_PATH + "/pkcs12")
				.allowingEmbeddedImageOutsideBaseDir(false)
				.embeddedImageAutoResolutionMustBeSuccesful(true)
				.withHTMLText("<img src=\"cid:cid_name\"/>")
				.appendTextHTML("<img src=\"" + RESOURCES_PATH + "/log4j2.xml\"/>");

		assertThatThrownBy(new ThrowableAssert.ThrowingCallable() {
			public void call() {
				emailPopulatingBuilder.buildEmail();
			}
		})
				.isInstanceOf(EmailException.class)
				.hasMessageContaining("Unable to dynamically resolve data source for the following image src: src/test/resources/log4j2.xml");
	}

	@Test
	public void testEmbeddingImagesWithDynamicDataSourceResolution_absoluteUrl()
			throws IOException {
		assumeThat(getUrl("http://www.simplejavamail.org")).isEqualTo(HttpURLConnection.HTTP_OK);

		final Email email = builder
				.withEmbeddedImageAutoResolutionForFiles(true)
				.withEmbeddedImageAutoResolutionForClassPathResources(true)
				.withEmbeddedImageAutoResolutionForURLs(true)
				.withHTMLText("<img src=\"cid:cid_name\"/>")
				.appendTextHTML("<img src=\"http://www.simplejavamail.org/download.html\"/>")
				.buildEmail();

		verifyEmbeddedImage(email, "Download Simple Java Mail");
	}

	@Test
	public void testEmbeddingImagesWithDynamicDataSourceResolution_relativeUrlWithNoBaseUrl() {
		assumeThat(getUrl("http://www.simplejavamail.org")).isEqualTo(HttpURLConnection.HTTP_OK);

		final EmailPopulatingBuilder emailPopulatingBuilder = builder
				.withEmbeddedImageAutoResolutionForFiles(true)
				.withEmbeddedImageAutoResolutionForClassPathResources(true)
				.withEmbeddedImageAutoResolutionForURLs(true)
				.embeddedImageAutoResolutionMustBeSuccesful(true)
				.withHTMLText("<img src=\"cid:cid_name\"/>")
				.appendTextHTML("<img src=\"download.html\"/>");

		assertThatThrownBy(new ThrowableAssert.ThrowingCallable() {
			public void call() {
				emailPopulatingBuilder.buildEmail();
			}
		})
				.isInstanceOf(EmailException.class)
				.hasMessageContaining("Unable to dynamically resolve data source for the following image src: download.html");
	}

	@Test
	public void testEmbeddingImagesWithDynamicDataSourceResolution_relativeUrlAWithBaseUrl()
			throws IOException {
		assumeThat(getUrl("http://www.simplejavamail.org")).isEqualTo(HttpURLConnection.HTTP_OK);

		final Email email = builder
				.withEmbeddedImageAutoResolutionForFiles(true)
				.withEmbeddedImageAutoResolutionForClassPathResources(true)
				.withEmbeddedImageAutoResolutionForURLs(true)
				.withEmbeddedImageBaseUrl(new URL("http://www.simplejavamail.org"))
				.withHTMLText("<img src=\"cid:cid_name\"/>")
				.appendTextHTML("<img src=\"download.html\"/>")
				.buildEmail();

		verifyEmbeddedImage(email, "Download Simple Java Mail");
	}

	@Test
	public void testEmbeddingImagesWithDynamicDataSourceResolution_relativeUrlBWithBaseUrl()
			throws IOException {
		assumeThat(getUrl("http://www.simplejavamail.org")).isEqualTo(HttpURLConnection.HTTP_OK);

		final Email email = builder
				.withEmbeddedImageAutoResolutionForFiles(true)
				.withEmbeddedImageAutoResolutionForClassPathResources(true)
				.withEmbeddedImageAutoResolutionForURLs(true)
				.withEmbeddedImageBaseUrl(new URL("http://www.simplejavamail.org"))
				.withHTMLText("<img src=\"cid:cid_name\"/>")
				.appendTextHTML("<img src=\"\\download.html\"/>")
				.buildEmail();

		verifyEmbeddedImage(email, "Download Simple Java Mail");
	}

	@Test
	public void testEmbeddingImagesWithDynamicDataSourceResolution_relativeUrlCWithBaseUrl()
			throws IOException {
		assumeThat(getUrl("http://www.simplejavamail.org")).isEqualTo(HttpURLConnection.HTTP_OK);

		final Email email = builder
				.withEmbeddedImageAutoResolutionForFiles(true)
				.withEmbeddedImageAutoResolutionForClassPathResources(true)
				.withEmbeddedImageAutoResolutionForURLs(true)
				.withEmbeddedImageBaseUrl(new URL("http://www.simplejavamail.org"))
				.withHTMLText("<img src=\"cid:cid_name\"/>")
				.appendTextHTML("<img src=\"/download.html\"/>")
				.buildEmail();

		verifyEmbeddedImage(email, "Download Simple Java Mail");
	}

	@Test
	public void testEmbeddingImagesWithDynamicDataSourceResolution_absoluteUrlNestedUnderBaseUrl_AllowFlagTrue()
			throws IOException {
		assumeThat(getUrl("http://www.simplejavamail.org")).isEqualTo(HttpURLConnection.HTTP_OK);

		final Email email = builder
				.withEmbeddedImageAutoResolutionForFiles(true)
				.withEmbeddedImageAutoResolutionForClassPathResources(true)
				.withEmbeddedImageAutoResolutionForURLs(true)
				.withEmbeddedImageBaseUrl(new URL("http://www.simplejavamail.org"))
				.allowingEmbeddedImageOutsideBaseUrl(true)
				.withHTMLText("<img src=\"cid:cid_name\"/>")
				.appendTextHTML("<img src=\"http://www.simplejavamail.org/download.html\"/>")
				.buildEmail();

		verifyEmbeddedImage(email, "Download Simple Java Mail");
	}

	@Test
	public void testEmbeddingImagesWithDynamicDataSourceResolution_absoluteUrlNestedUnderBaseUrl_AllowFlagFalseSameAsTrue()
			throws IOException {
		assumeThat(getUrl("http://www.simplejavamail.org")).isEqualTo(HttpURLConnection.HTTP_OK);

		final Email email = builder
				.withEmbeddedImageAutoResolutionForFiles(true)
				.withEmbeddedImageAutoResolutionForClassPathResources(true)
				.withEmbeddedImageAutoResolutionForURLs(true)
				.withEmbeddedImageBaseUrl(new URL("http://www.simplejavamail.org"))
				.allowingEmbeddedImageOutsideBaseUrl(false)
				.withHTMLText("<img src=\"cid:cid_name\"/>")
				.appendTextHTML("<img src=\"http://www.simplejavamail.org/download.html\"/>")
				.buildEmail();

		verifyEmbeddedImage(email, "Download Simple Java Mail");
	}

	@Test
	public void testEmbeddingImagesWithDynamicDataSourceResolution_absoluteUrlOutsideBaseUrl_AllowedTrue()
			throws IOException {
		assumeThat(getUrl("http://www.simplejavamail.org")).isEqualTo(HttpURLConnection.HTTP_OK);

		final Email email = builder
				.withEmbeddedImageAutoResolutionForFiles(true)
				.withEmbeddedImageAutoResolutionForClassPathResources(true)
				.withEmbeddedImageAutoResolutionForURLs(true)
				.withEmbeddedImageBaseUrl(new URL("http://www.simplejavamail.org/download.html"))
				.allowingEmbeddedImageOutsideBaseUrl(true)
				.withHTMLText("<img src=\"cid:cid_name\"/>")
				.appendTextHTML("<img src=\"http://www.simplejavamail.org\"/>")
				.buildEmail();

		verifyEmbeddedImage(email, "Download Simple Java Mail");
	}

	@Test
	public void testEmbeddingImagesWithDynamicDataSourceResolution_absoluteUrlOutsideBaseUrl_AllowedFalse()
			throws IOException {
		assumeThat(getUrl("http://www.simplejavamail.org")).isEqualTo(HttpURLConnection.HTTP_OK);

		final EmailPopulatingBuilder emailPopulatingBuilder = builder
				.withEmbeddedImageAutoResolutionForFiles(true)
				.withEmbeddedImageAutoResolutionForClassPathResources(true)
				.withEmbeddedImageAutoResolutionForURLs(true)
				.withEmbeddedImageBaseUrl(new URL("http://www.simplejavamail.org/download/"))
				.allowingEmbeddedImageOutsideBaseUrl(false)
				.embeddedImageAutoResolutionMustBeSuccesful(true)
				.withHTMLText("<img src=\"cid:cid_name\"/>")
				.appendTextHTML("<img src=\"http://www.simplejavamail.org/download.html\"/>");

		assertThatThrownBy(new ThrowableAssert.ThrowingCallable() {
			public void call() {
				emailPopulatingBuilder.buildEmail();
			}
		})
				.isInstanceOf(EmailException.class)
				.hasMessageContaining("Unable to dynamically resolve data source for the following image src: http://www.simplejavamail.org/download.html");
	}

	@Test
	public void testEmbeddingImagesWithDynamicDataSourceResolution_classPathPath()
			throws IOException {
		final Email email = builder
				.withEmbeddedImageAutoResolutionForFiles(true)
				.withEmbeddedImageAutoResolutionForClassPathResources(true)
				.withEmbeddedImageAutoResolutionForURLs(true)
				.withHTMLText("<img src=\"cid:cid_name\"/>")
				.appendTextHTML("<img src=\"/pkcs12/how-to.html\"/>")
				.buildEmail();

		verifyEmbeddedImage(email, "Create Self-Signed S/MIME Certificates");
	}

	@Test
	public void testEmbeddingImagesWithDynamicDataSourceResolution_relativeClassPathWithNoClassPathBase() {
		final EmailPopulatingBuilder emailPopulatingBuilder = builder
				.withEmbeddedImageAutoResolutionForFiles(true)
				.withEmbeddedImageAutoResolutionForClassPathResources(true)
				.withEmbeddedImageAutoResolutionForURLs(true)
				.embeddedImageAutoResolutionMustBeSuccesful(true)
				.withHTMLText("<img src=\"cid:cid_name\"/>")
				.appendTextHTML("<img src=\"/how-to.html\"/>");

		assertThatThrownBy(new ThrowableAssert.ThrowingCallable() {
			public void call() {
				emailPopulatingBuilder.buildEmail();
			}
		})
				.isInstanceOf(EmailException.class)
				.hasMessageContaining("Unable to dynamically resolve data source for the following image src: /how-to.html");
	}

	@Test
	public void testEmbeddingImagesWithDynamicDataSourceResolution_relativeClassPathWithClassPathBase()
			throws IOException {
		final Email email = builder
				.withEmbeddedImageAutoResolutionForFiles(true)
				.withEmbeddedImageAutoResolutionForClassPathResources(true)
				.withEmbeddedImageAutoResolutionForURLs(true)
				.withEmbeddedImageBaseClassPath("/pkcs12")
				.withHTMLText("<img src=\"cid:cid_name\"/>")
				.appendTextHTML("<img src=\"/how-to.html\"/>")
				.buildEmail();

		verifyEmbeddedImage(email, "Create Self-Signed S/MIME Certificates");
	}

	@Test
	public void testEmbeddingImagesWithDynamicDataSourceResolution_absoluteClassPathNestedUnderBaseClassPath_AllowFlagTrue()
			throws IOException {
		final Email email = builder
				.withEmbeddedImageAutoResolutionForFiles(true)
				.withEmbeddedImageAutoResolutionForClassPathResources(true)
				.withEmbeddedImageAutoResolutionForURLs(true)
				.withEmbeddedImageBaseClassPath("/pkcs12")
				.allowingEmbeddedImageOutsideBaseClassPath(true)
				.withHTMLText("<img src=\"cid:cid_name\"/>")
				.appendTextHTML("<img src=\"/pkcs12/how-to.html\"/>")
				.buildEmail();

		verifyEmbeddedImage(email, "Create Self-Signed S/MIME Certificates");
	}

	@Test
	public void testEmbeddingImagesWithDynamicDataSourceResolution_absoluteClassPathNestedUnderBaseClassPath_AllowFlagFalseSameAstrue()
			throws IOException {
		final Email email = builder
				.withEmbeddedImageAutoResolutionForFiles(true)
				.withEmbeddedImageAutoResolutionForClassPathResources(true)
				.withEmbeddedImageAutoResolutionForURLs(true)
				.withEmbeddedImageBaseClassPath("/pkcs12")
				.allowingEmbeddedImageOutsideBaseClassPath(false)
				.withHTMLText("<img src=\"cid:cid_name\"/>")
				.appendTextHTML("<img src=\"/pkcs12/how-to.html\"/>")
				.buildEmail();

		verifyEmbeddedImage(email, "Create Self-Signed S/MIME Certificates");
	}

	@Test
	public void testEmbeddingImagesWithDynamicDataSourceResolution_absoluteClassPathOutsideBaseClassPath_AllowFlagTrue()
			throws IOException {
		final Email email = builder
				.withEmbeddedImageAutoResolutionForFiles(true)
				.withEmbeddedImageAutoResolutionForClassPathResources(true)
				.withEmbeddedImageAutoResolutionForURLs(true)
				.withEmbeddedImageBaseClassPath("/pkcs12")
				.allowingEmbeddedImageOutsideBaseClassPath(true)
				.withHTMLText("<img src=\"cid:cid_name\"/>")
				.appendTextHTML("<img src=\"/log4j2.xml\"/>")
				.buildEmail();

		verifyEmbeddedImage(email, "<Console name=\"console\" target=\"SYSTEM_OUT\">");
	}

	@Test
	public void testEmbeddingImagesWithDynamicDataSourceResolution_absoluteClassPathOutsideBaseClassPath_AllowFlagFalse() {
		final EmailPopulatingBuilder emailPopulatingBuilder = builder
				.withEmbeddedImageAutoResolutionForFiles(true)
				.withEmbeddedImageAutoResolutionForClassPathResources(true)
				.withEmbeddedImageAutoResolutionForURLs(true)
				.withEmbeddedImageBaseClassPath("/pkcs12")
				.allowingEmbeddedImageOutsideBaseClassPath(false)
				.embeddedImageAutoResolutionMustBeSuccesful(true)
				.withHTMLText("<img src=\"cid:cid_name\"/>")
				.appendTextHTML("<img src=\"/log4j2.xml\"/>");

		assertThatThrownBy(new ThrowableAssert.ThrowingCallable() {
			public void call() {
				emailPopulatingBuilder.buildEmail();
			}
		})
				.isInstanceOf(EmailException.class)
				.hasMessageContaining("Unable to dynamically resolve data source for the following image src: /log4j2.xml");
	}

	private void verifyEmbeddedImage(final Email email, String expectedContainsWithContent)
			throws IOException {
		final String cidRegex = "<img src=\"cid:cid_name\"/><img src=\"cid:(?<cid>[a-z]{10})\"/>";
		assertThat(email.getHTMLText()).matches(cidRegex);
		final Matcher matcher = Pattern.compile(cidRegex).matcher(email.getHTMLText());
		assertThat(matcher.find()).isTrue();
		assertThat(email.getEmbeddedImages()).hasSize(1);
		assertThat(email.getEmbeddedImages().get(0).getName()).isEqualTo(matcher.group("cid"));
		assertThat(email.getEmbeddedImages().get(0).readAllData()).contains(expectedContainsWithContent);
	}

	@Test
	public void testClearingValues() throws IOException {
		EmailPopulatingBuilder emailBuilder = EmailHelper.createDummyEmailBuilder("<id>", true, false, true, true, true, false)
				.notMergingSingleSMIMESignedAttachment()
				.signWithDomainKey("dkim_key", "dkim_domain", "dkim_selector")
				.signWithSmime(new ByteArrayInputStream(new byte[]{}), "storePassword", "keyAlias", "keyPassword")
				.encryptWithSmime(mock(X509Certificate.class));

		assertThat(emailBuilder.isMergeSingleSMIMESignedAttachment()).isFalse();

		Email emailNormal = emailBuilder.buildEmail();

		assertThat(emailNormal.getId()).isNotNull();
		assertThat(emailNormal.getSubject()).isNotNull();
		assertThat(emailNormal.getBounceToRecipient()).isNotNull();
		assertThat(emailNormal.getDispositionNotificationTo()).isNotNull();
		assertThat(emailNormal.getDkimPrivateKeyData()).isNotNull();
		assertThat(emailNormal.getDkimSelector()).isNotNull();
		assertThat(emailNormal.getDkimSigningDomain()).isNotNull();
		assertThat(emailNormal.getAttachments()).isNotEmpty();
		assertThat(emailNormal.getEmbeddedImages()).isNotEmpty();
		assertThat(emailNormal.getFromRecipient()).isNotNull();
		assertThat(emailNormal.getHeaders()).isNotEmpty();
		assertThat(emailNormal.getHTMLText()).isNotNull();
		assertThat(emailNormal.getPlainText()).isNotNull();
		assertThat(emailNormal.getRecipients()).isNotEmpty();
		assertThat(emailNormal.getReplyToRecipient()).isNotNull();
		assertThat(emailNormal.getReturnReceiptTo()).isNotNull();
		assertThat(emailNormal.getSentDate()).isNotNull();
		assertThat(emailNormal.getPkcs12ConfigForSmimeSigning()).isNotNull();
		assertThat(emailNormal.getX509CertificateForSmimeEncryption()).isNotNull();

		emailBuilder
				.clearId()
				.clearSubject()
				.clearBounceTo()
				.clearDispositionNotificationTo()
				.clearDkim()
				.clearAttachments()
				.clearEmbeddedImages()
				.clearFromRecipient()
				.clearHeaders()
				.clearHTMLText()
				.clearPlainText()
				.clearRecipients()
				.clearReplyTo()
				.clearReturnReceiptTo()
				.clearSentDate()
				.clearSmime()
				.clearSMIMESignedAttachmentMergingBehavior();

		assertThat(emailBuilder.isMergeSingleSMIMESignedAttachment()).isTrue();

		Email emailCleared = emailBuilder.buildEmail();

		assertThat(emailCleared.getId()).isNull();
		assertThat(emailCleared.getSubject()).isNull();
		assertThat(emailCleared.getBounceToRecipient()).isNull();
		assertThat(emailCleared.getDispositionNotificationTo()).isNull();
		assertThat(emailCleared.getDkimPrivateKeyData()).isNull();
		assertThat(emailCleared.getDkimSelector()).isNull();
		assertThat(emailCleared.getDkimSigningDomain()).isNull();
		assertThat(emailCleared.getAttachments()).isEmpty();
		assertThat(emailCleared.getEmbeddedImages()).isEmpty();
		assertThat(emailCleared.getFromRecipient()).isNull();
		assertThat(emailCleared.getHeaders()).isEmpty();
		assertThat(emailCleared.getHTMLText()).isNull();
		assertThat(emailCleared.getPlainText()).isNull();
		assertThat(emailCleared.getRecipients()).isEmpty();
		assertThat(emailCleared.getReplyToRecipient()).isNull();
		assertThat(emailCleared.getReturnReceiptTo()).isNull();
		assertThat(emailCleared.getSentDate()).isNull();
		assertThat(emailCleared.getPkcs12ConfigForSmimeSigning()).isNull();
		assertThat(emailCleared.getX509CertificateForSmimeEncryption()).isNull();
	}

	@Test
	public void testClearingValuesAlternativeFlows() throws IOException {
		EmailPopulatingBuilder emailBuilder = EmailHelper.createDummyEmailBuilder("<id>", true, false, true, true, true, false)
				.signWithDomainKey(new ByteArrayInputStream(new byte[]{}), "dkim_domain", "dkim_selector");

		Email emailNormal = emailBuilder.buildEmail();

		assertThat(emailNormal.getDkimPrivateKeyData()).isNotNull();
		assertThat(emailNormal.getDkimSelector()).isNotNull();
		assertThat(emailNormal.getDkimSigningDomain()).isNotNull();

		emailBuilder
				.clearDkim();

		Email emailCleared = emailBuilder.buildEmail();

		assertThat(emailCleared.getDkimPrivateKeyData()).isNull();
		assertThat(emailCleared.getDkimSelector()).isNull();
		assertThat(emailCleared.getDkimSigningDomain()).isNull();
	}

	@Test
	public void testEncryptWithSmime_FromCertificate() throws Exception {
		Security.addProvider(new BouncyCastleProvider());

		X509Certificate certificateIn = CertificationUtil.readFromPem(new File(RESOURCES_PKCS + "/smime_test_user.pem.standard.crt"));

		builder.encryptWithSmime(certificateIn);

		final X509Certificate certificateOut = builder.buildEmail().getX509CertificateForSmimeEncryption();

		assertThat(certificateOut).isNotNull();
		assertSignedBy(certificateOut, "Benny Bottema");
	}

	private void assertSignedBy(X509Certificate certificate, @SuppressWarnings("SameParameterValue") final String expectedSignedBy)
			throws OperatorCreationException {
		assertThat(extractSignedBy(certificate)).isEqualTo(expectedSignedBy);
	}

	private Recipient createRecipient(final @Nullable String name, final String emailAddress, final Message.RecipientType recipientType) {
		return new Recipient(name, emailAddress, recipientType);
	}

	private static class DataSourceWithDummyName implements DataSource {
		@Override
		public InputStream getInputStream() {
			return null;
		}

		@Override
		public OutputStream getOutputStream() {
			return null;
		}

		@Override
		public String getContentType() {
			return "text/txt";
		}

		@Override
		public String getName() {
			return "dummy";
		}
	}
}