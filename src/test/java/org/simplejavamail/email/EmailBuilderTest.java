package org.simplejavamail.email;

import org.junit.Before;
import org.junit.Test;
import testutil.ConfigLoaderTestHelper;

import javax.activation.DataSource;
import javax.mail.Message;
import javax.mail.util.ByteArrayDataSource;
import java.io.InputStream;
import java.io.OutputStream;

import static javax.mail.Message.RecipientType.BCC;
import static javax.mail.Message.RecipientType.CC;
import static javax.mail.Message.RecipientType.TO;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.failBecauseExceptionWasNotThrown;

public class EmailBuilderTest {
	
	private EmailBuilder builder;
	
	@Before
	public void setup() throws Exception {
		ConfigLoaderTestHelper.clearConfigProperties();
		builder = EmailBuilder.builder();
	}

	@Test
	public void testBuilderFromAddress() {
		final Email email = builder
				.from(new Recipient("lollypop", "lol.pop@somemail.com", null))
				.build();

		assertThat(email.getFromRecipient().getName()).isEqualTo("lollypop");
		assertThat(email.getFromRecipient().getAddress()).isEqualTo("lol.pop@somemail.com");
		assertThat(email.getFromRecipient().getType()).isNull();
	}

	@Test
	public void testBuilderFromAddressOverwriteWithAlternativeBuilderMethod() {
		final Email email = builder
				.from("lollypop", "lol.pop@somemail.com") // should be overwritted
				.from(new Recipient("lollypop2", "lol.pop2@somemail.com", null))
				.build();

		assertThat(email.getFromRecipient().getName()).isEqualTo("lollypop2");
		assertThat(email.getFromRecipient().getAddress()).isEqualTo("lol.pop2@somemail.com");
		assertThat(email.getFromRecipient().getType()).isNull();
	}
	
	@Test
	public void testBuilderReplyToAddress() {
		final Email email = builder
				.replyTo(new Recipient("lollypop", "lol.pop@somemail.com", null))
				.build();
		
		assertThat(email.getReplyToRecipient().getName()).isEqualTo("lollypop");
		assertThat(email.getReplyToRecipient().getAddress()).isEqualTo("lol.pop@somemail.com");
		assertThat(email.getReplyToRecipient().getType()).isNull();
	}
	
	@Test
	public void testBuilderBounceToAddress() {
		final Email email = builder
				.bounceTo(new Recipient("lollypop", "lol.pop@somemail.com", null))
				.build();
		
		assertThat(email.getBounceToRecipient().getName()).isEqualTo("lollypop");
		assertThat(email.getBounceToRecipient().getAddress()).isEqualTo("lol.pop@somemail.com");
		assertThat(email.getBounceToRecipient().getType()).isNull();
	}
	
	@Test
	public void testBuilderReplyToAddressOverwriteWithAlternativeBuilderMethod() {
		final Email email = builder
				.replyTo("lollypop", "lol.pop@somemail.com") // should be overwritted
				.replyTo(new Recipient("lollypop2", "lol.pop2@somemail.com", null))
				.build();
		
		assertThat(email.getReplyToRecipient().getName()).isEqualTo("lollypop2");
		assertThat(email.getReplyToRecipient().getAddress()).isEqualTo("lol.pop2@somemail.com");
		assertThat(email.getReplyToRecipient().getType()).isNull();
	}
	
	@Test
	public void testBuilderBounceToAddressOverwriteWithAlternativeBuilderMethod() {
		final Email email = builder
				.bounceTo("lollypop", "lol.pop@somemail.com") // should be overwritted
				.bounceTo(new Recipient("lollypop2", "lol.pop2@somemail.com", null))
				.build();
		
		assertThat(email.getBounceToRecipient().getName()).isEqualTo("lollypop2");
		assertThat(email.getBounceToRecipient().getAddress()).isEqualTo("lol.pop2@somemail.com");
		assertThat(email.getBounceToRecipient().getType()).isNull();
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
				.build();
		
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
				.build();
		
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
				.build();

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
				.build();
		
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
				.build();

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
				.build();
		
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
				.replyTo("replyTo", "1@candyshop.org")
				.from("from", "2@candyshop.org")
				.build();
		
		assertThat(email.isUseDispositionNotificationTo()).isFalse();
		assertThat(email.isUseReturnReceiptTo()).isFalse();
		assertThat(email.getDispositionNotificationTo()).isNull();
		assertThat(email.getReturnReceiptTo()).isNull();
	}
	
	@Test
	public void testBuilderNotificationFlags_ReDefaultToReplyTo() {
		final Email email = builder
				.replyTo("replyTo", "1@candyshop.org")
				.from("from", "2@candyshop.org")
				.withDispositionNotificationTo("custom@candyshop.com")
				.withReturnReceiptTo("custom@candyshop.com")
				.withDispositionNotificationTo()
				.withReturnReceiptTo()
				.build();
		
		assertThat(email.isUseDispositionNotificationTo()).isTrue();
		assertThat(email.isUseReturnReceiptTo()).isTrue();
		assertThat(email.getDispositionNotificationTo()).isEqualTo(new Recipient("replyTo", "1@candyshop.org", null));
		assertThat(email.getReturnReceiptTo()).isEqualTo(new Recipient("replyTo", "1@candyshop.org", null));
	}
	
	@Test
	public void testBuilderNotificationFlags_DefaultToReplyTo() {
		final Email email = builder
				.replyTo("replyTo", "1@candyshop.org")
				.from("from", "2@candyshop.org")
				.withDispositionNotificationTo()
				.withReturnReceiptTo()
				.build();
		
		assertThat(email.isUseDispositionNotificationTo()).isTrue();
		assertThat(email.isUseReturnReceiptTo()).isTrue();
		assertThat(email.getDispositionNotificationTo()).isEqualTo(new Recipient("replyTo", "1@candyshop.org", null));
		assertThat(email.getReturnReceiptTo()).isEqualTo(new Recipient("replyTo", "1@candyshop.org", null));
	}
	
	@Test
	public void testBuilderNotificationFlags_CustomAddress() {
		final Email email = builder
				.replyTo("replyTo", "1@candyshop.org")
				.from("from", "2@candyshop.org")
				.withDispositionNotificationTo("customa@candyshop.org")
				.withReturnReceiptTo("customb@candyshop.org")
				.build();
		
		assertThat(email.isUseDispositionNotificationTo()).isTrue();
		assertThat(email.isUseReturnReceiptTo()).isTrue();
		assertThat(email.getDispositionNotificationTo()).isEqualTo(new Recipient(null, "customa@candyshop.org", null));
		assertThat(email.getReturnReceiptTo()).isEqualTo(new Recipient(null, "customb@candyshop.org", null));
	}

	@Test
	public void testBuilderEmbeddingImages() {
		builder
				.embedImage("a", new ByteArrayDataSource(new byte[3], ""))
				.embedImage(null, new DataSourceWithDummyName())
				.embedImage("a", new byte[3], "mimetype");
		try {
			builder.embedImage(null, new ByteArrayDataSource(new byte[3], ""));
			failBecauseExceptionWasNotThrown(EmailException.class);
		} catch (EmailException e) {
			// ok
		}
		try {
			builder.embedImage(null, new byte[3], "mimetype");
			failBecauseExceptionWasNotThrown(IllegalArgumentException.class);
		} catch (IllegalArgumentException e) {
			// ok
		}
	}

	@Test
	public void testBuilderAddingAttachmentsWithMissingNameWithoutExceptions() {
		builder
				.addAttachment("a", new ByteArrayDataSource(new byte[3], "text/txt"))
				.addAttachment(null, new DataSourceWithDummyName())
				.addAttachment("a", new byte[3], "text/txt")
				.addAttachment(null, new ByteArrayDataSource(new byte[3], "text/txt"))
				.addAttachment(null, new byte[3], "text/txt");
		// ok no exceptions
	}
	
	@Test
	public void testPrependText_ToEmptyText() {
		Email test = builder
				.prependText("test")
				.build();
		
		EmailAssert.assertThat(test).hasText("test");
	}
	
	@Test
	public void testPrependText_ToNonEmptyText() {
		Email test = builder
				.text("moo")
				.textHTML("ignore")
				.prependText("test\n")
				.build();
		
		EmailAssert.assertThat(test).hasText("test\nmoo");
	}
	
	@Test
	public void testPrependTextHTML_ToEmptyText() {
		Email test = builder
				.prependTextHTML("test")
				.build();
		
		EmailAssert.assertThat(test).hasTextHTML("test");
	}
	
	@Test
	public void testPrependTextHTML_ToNonEmptyText() {
		Email test = builder
				.text("ignore")
				.textHTML("moo")
				.prependTextHTML("test\n")
				.build();
		
		EmailAssert.assertThat(test).hasTextHTML("test\nmoo");
	}
	
	@Test
	public void testAppendText_ToEmptyText() {
		Email test = builder
				.appendText("test")
				.build();
		
		EmailAssert.assertThat(test).hasText("test");
	}
	
	@Test
	public void testAppendText_ToNonEmptyText() {
		Email test = builder
				.text("moo")
				.textHTML("ignore")
				.appendText("\ntest")
				.build();
		
		EmailAssert.assertThat(test).hasText("moo\ntest");
	}
	
	@Test
	public void testAppendTextHTML_ToEmptyText() {
		Email test = builder
				.appendTextHTML("test")
				.build();
		
		EmailAssert.assertThat(test).hasTextHTML("test");
	}
	
	@Test
	public void testAppendTextHTML_ToNonEmptyText() {
		Email test = builder
				.text("ignore")
				.textHTML("moo")
				.appendTextHTML("\ntest")
				.build();
		
		EmailAssert.assertThat(test).hasTextHTML("moo\ntest");
	}
	
	@Test
	public void testAddRecipients_Basic_Named() {
		builder.to("name1", "1@domain.com");
		builder.cc("name2", "2@domain.com,3@domain.com");
		builder.bcc("name3", "4@domain.com;5@domain.com");
		builder.to("name4", "6@domain.com;7@domain.com,8@domain.com");
		
		assertThat(builder.build().getRecipients()).containsExactlyInAnyOrder(
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
		
		assertThat(builder.build().getRecipients()).containsExactlyInAnyOrder(
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
		
		assertThat(builder.build().getRecipients()).containsExactlyInAnyOrder(
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
		
		assertThat(builder.build().getRecipients()).containsExactlyInAnyOrder(
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
		
		assertThat(builder.build().getRecipients()).containsExactlyInAnyOrder(
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

	private Recipient createRecipient(final String name, final String emailAddress, final Message.RecipientType recipientType) {
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