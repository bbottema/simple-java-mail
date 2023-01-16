package org.simplejavamail.mailer;

import jakarta.mail.Session;
import jakarta.mail.internet.MimeMessage;
import jakarta.mail.util.ByteArrayDataSource;
import org.junit.Before;
import org.junit.Test;
import org.simplejavamail.api.email.Email;
import org.simplejavamail.api.mailer.CustomMailer;
import org.simplejavamail.api.mailer.config.OperationalConfig;
import org.simplejavamail.config.ConfigLoader;
import org.simplejavamail.internal.util.NamedDataSource;
import testutil.EmailHelper;

import java.io.ByteArrayInputStream;
import java.io.IOException;

import static demo.ResourceFolderHelper.determineResourceFolder;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.simplejavamail.mailer.MailerTest.createFullyConfiguredMailerBuilder;

@SuppressWarnings("unused")
public class MailerInjectionScanTest {

	private static final String RESOURCES_PKCS = determineResourceFolder("simple-java-mail") + "/test/resources/pkcs12";

	@Before
	public void restoreOriginalStaticProperties() {
		ConfigLoader.loadProperties(new ByteArrayInputStream("simplejavamail.javaxmail.debug=true".getBytes()), false);
	}

	@Test
	public void testCustomMailer_sendEmail_dontFailOn_injectionAttackOrInvalidAddress() throws IOException {
		final CustomMailer customMailerMock = mock(CustomMailer.class);

		final Email email = EmailHelper.createDummyEmailBuilder(true, false, false, true, false, false)
				// example from https://framework.zend.com/security/advisory/ZF2015-04
				.withSubject("test1\r\nContent-Type: text/html; charset = \"iso-8859-1\"\r\n\r\n" +
						"<iframe src=\"http://example.com/\"></iframe><!--")
				.from("evil %0A boy", "badboy\nis\rsobad@hell.spawn")
				.withHeader("dark pie", "evil\nlaugh")
				.buildEmail();

		createFullyConfiguredMailerBuilder(false, "", null)
				.withCustomMailer(customMailerMock)
				.disablingAllClientValidation(true).buildMailer()
				.sendMail(email);

		verify(customMailerMock).sendMessage(any(OperationalConfig.class), any(Session.class), any(MimeMessage.class));
		verifyNoMoreInteractions(customMailerMock);
	}

	@Test
	public void testCustomMailer_sendEmail_failOn_injectionAttack_subject() {
		assertThatThrownBy(() -> createFullyConfiguredMailerBuilder(false, "", null).buildMailer()
				.sendMail(EmailHelper.createDummyEmailBuilder(true, false, false, true, false, false)
						// example from https://framework.zend.com/security/advisory/ZF2015-04
						.withSubject("test1\r\nContent-Type: text/html; charset = \"iso-8859-1\"\r\n\r\n" +
								"<iframe src=\"http://example.com/\"></iframe><!--")
						.buildEmail()))
				.isInstanceOf(MailSuspiciousCRLFValueException.class)
				.hasMessageStartingWith("Suspected of injection attack, field: email.subject with suspicious value: test1\r\n");
	}

	@Test
	public void testCustomMailer_sendEmail_failOn_injectionAttack_subject_after_disableReenable() {
		assertThatThrownBy(() -> createFullyConfiguredMailerBuilder(false, "", null)
				.disablingAllClientValidation(true)
				.resetDisableAllClientValidations()
				.buildMailer()
				.sendMail(EmailHelper.createDummyEmailBuilder(true, false, false, true, false, false)
						// example from https://framework.zend.com/security/advisory/ZF2015-04
						.withSubject("test1\r\nContent-Type: text/html; charset = \"iso-8859-1\"\r\n\r\n" +
								"<iframe src=\"http://example.com/\"></iframe><!--")
						.buildEmail()))
				.isInstanceOf(MailSuspiciousCRLFValueException.class)
				.hasMessageStartingWith("Suspected of injection attack, field: email.subject with suspicious value: test1\r\n");
	}

	@Test
	public void testCustomMailer_sendEmail_failOn_injectionAttack_fromRecipient() {
		assertThatThrownBy(() -> createFullyConfiguredMailerBuilder(false, "", null).buildMailer()
				.sendMail(EmailHelper.createDummyEmailBuilder(true, false, false, true, false, false)
						// passes address validation, but %0A is a URL-encoded newline using in CRLF injection attacks
						.from("naughty%0Atooth@notsosweet.hell")
						.withReturnReceiptTo().buildEmail()))
				.isInstanceOf(MailSuspiciousCRLFValueException.class)
				.hasMessage("Suspected of injection attack, field: email.fromRecipient.address with suspicious value: naughty%0Atooth@notsosweet.hell");
	}

	@Test
	public void testCustomMailer_sendEmail_failOn_injectionAttack_replyToRecipient() {
		assertThatThrownBy(() -> createFullyConfiguredMailerBuilder(false, "", null).buildMailer()
				.sendMail(EmailHelper.createDummyEmailBuilder(true, false, false, true, false, false)
						// passes address validation, but %0A is a URL-encoded newline using in CRLF injection attacks
						.withReplyTo("naughty%0Atooth@notsosweet.hell")
						.buildEmail()))
				.isInstanceOf(MailSuspiciousCRLFValueException.class)
				.hasMessage("Suspected of injection attack, field: email.replyToRecipient.address with suspicious value: naughty%0Atooth@notsosweet.hell");
	}

	@Test
	public void testCustomMailer_sendEmail_failOn_injectionAttack_bounceTo() {
		assertThatThrownBy(() -> createFullyConfiguredMailerBuilder(false, "", null).buildMailer()
				.sendMail(EmailHelper.createDummyEmailBuilder(true, false, false, true, false, false)
						// passes address validation, but %0A is a URL-encoded newline using in CRLF injection attacks
						.withBounceTo("naughty%0Atooth@notsosweet.hell").buildEmail()))
				.isInstanceOf(MailSuspiciousCRLFValueException.class)
				.hasMessage("Suspected of injection attack, field: email.bounceToRecipient.address with suspicious value: naughty%0Atooth@notsosweet.hell");
	}

	@Test
	public void testCustomMailer_sendEmail_failOn_injectionAttack_DispositionNotificationTo() {
		assertThatThrownBy(() -> createFullyConfiguredMailerBuilder(false, "", null).buildMailer()
				.sendMail(EmailHelper.createDummyEmailBuilder(true, false, false, true, false, false)
						// passes address validation, but %0A is a URL-encoded newline using in CRLF injection attacks
						.withDispositionNotificationTo("naughty%0Atooth@notsosweet.hell").buildEmail()))
				.isInstanceOf(MailSuspiciousCRLFValueException.class)
				.hasMessage("Suspected of injection attack, field: email.dispositionNotificationTo.address with suspicious value: naughty%0Atooth@notsosweet.hell");
	}

	@Test
	public void testCustomMailer_sendEmail_failOn_injectionAttack_ReturnReceiptTo() {
		assertThatThrownBy(() -> createFullyConfiguredMailerBuilder(false, "", null).buildMailer()
				.sendMail(EmailHelper.createDummyEmailBuilder(true, false, false, true, false, false)
						// passes address validation, but %0A is a URL-encoded newline using in CRLF injection attacks
						.withReturnReceiptTo("naughty%0Atooth@notsosweet.hell").buildEmail()))
				.isInstanceOf(MailSuspiciousCRLFValueException.class)
				.hasMessage("Suspected of injection attack, field: email.returnReceiptTo.address with suspicious value: naughty%0Atooth@notsosweet.hell");
	}

	@Test
	public void testCustomMailer_sendEmail_failOn_injectionAttack_DispositionNotificationToName() {
		assertThatThrownBy(() -> createFullyConfiguredMailerBuilder(false, "", null).buildMailer()
				.sendMail(EmailHelper.createDummyEmailBuilder(true, false, false, true, false, false)
						// passes address validation, but %0A is a URL-encoded newline using in CRLF injection attacks
						.withDispositionNotificationTo("naughty %0A tooth", "sweetytooth@notsosweet.hell").buildEmail()))
				.isInstanceOf(MailSuspiciousCRLFValueException.class)
				.hasMessage("Suspected of injection attack, field: email.dispositionNotificationTo.name with suspicious value: naughty %0A tooth");
	}

	@Test
	public void testCustomMailer_sendEmail_failOn_injectionAttack_RecipientAddress() {
		assertThatThrownBy(() -> createFullyConfiguredMailerBuilder(false, "", null).buildMailer()
				.sendMail(EmailHelper.createDummyEmailBuilder(true, false, false, true, false, false)
						.cc("sweety pie <naughty%0Apie@evil.laugh>").buildEmail()))
				.isInstanceOf(MailSuspiciousCRLFValueException.class)
				.hasMessage("Suspected of injection attack, field: email.recipient.address with suspicious value: naughty%0Apie@evil.laugh");

		assertThatThrownBy(() -> createFullyConfiguredMailerBuilder(false, "", null).buildMailer()
				.sendMail(EmailHelper.createDummyEmailBuilder(true, false, false, true, false, false)
						.to("naughty%0Apie <sweety_pie@evil.laugh>").buildEmail()))
				.isInstanceOf(MailSuspiciousCRLFValueException.class)
				.hasMessage("Suspected of injection attack, field: email.recipient.name with suspicious value: naughty%0Apie");

		assertThatThrownBy(() -> createFullyConfiguredMailerBuilder(false, "", null).buildMailer()
				.sendMail(EmailHelper.createDummyEmailBuilder(true, false, false, true, false, false)
						.bcc("sweety pie <sweety_pie@evil.laugh>, evil%0Alaugh <sweety_pie@evil.laugh>").buildEmail()))
				.isInstanceOf(MailSuspiciousCRLFValueException.class)
				.hasMessage("Suspected of injection attack, field: email.recipient.name with suspicious value: evil%0Alaugh");
	}

	@Test
	public void testCustomMailer_sendEmail_failOn_attachmentName() {
		assertThatThrownBy(() -> createFullyConfiguredMailerBuilder(false, "", null).buildMailer()
				.sendMail(EmailHelper.createDummyEmailBuilder(true, false, false, true, false, false)
						.clearEmbeddedImages()
						.withEmbeddedImage("naughty\ntooth", "moomoo".getBytes(), "text/plain")
						.buildEmail()))
				.isInstanceOf(MailSuspiciousCRLFValueException.class)
				.hasMessage("Suspected of injection attack, field: email.embeddedImage.name with suspicious value: naughty\ntooth");

		assertThatThrownBy(() -> createFullyConfiguredMailerBuilder(false, "", null).buildMailer()
				.sendMail(EmailHelper.createDummyEmailBuilder(true, false, false, true, false, false)
						.clearAttachments()
						.withAttachment("naughty\ntooth", "moomoo".getBytes(), "text/plain")
						.buildEmail()))
				.isInstanceOf(MailSuspiciousCRLFValueException.class)
				.hasMessage("Suspected of injection attack, field: email.attachment.name with suspicious value: naughty\ntooth");
	}

	@Test
	public void testCustomMailer_sendEmail_failOn_attachmentNestedName() {
		assertThatThrownBy(() -> createFullyConfiguredMailerBuilder(false, "", null).buildMailer()
				.sendMail(EmailHelper.createDummyEmailBuilder(true, false, false, true, false, false)
						// example from https://framework.zend.com/security/advisory/ZF2015-04
						.clearEmbeddedImages()
						.withEmbeddedImage("sweety tooth", new NamedDataSource("naughty\ntooth", new ByteArrayDataSource("moomoo".getBytes(), "text/plain")))
						.buildEmail()))
				.isInstanceOf(MailSuspiciousCRLFValueException.class)
				.hasMessage("Suspected of injection attack, field: email.embeddedImage.datasource.name with suspicious value: naughty\ntooth");

		assertThatThrownBy(() -> createFullyConfiguredMailerBuilder(false, "", null).buildMailer()
				.sendMail(EmailHelper.createDummyEmailBuilder(true, false, false, true, false, false)
						.clearAttachments()
						.withAttachment("sweety tooth", new NamedDataSource("naughty\ntooth", new ByteArrayDataSource("moomoo".getBytes(), "text/plain")))
						.buildEmail()))
				.isInstanceOf(MailSuspiciousCRLFValueException.class)
				.hasMessage("Suspected of injection attack, field: email.attachment.datasource.name with suspicious value: naughty\ntooth");
	}

	@Test
	public void testCustomMailer_sendEmail_failOn_attachmentDescription() {
		assertThatThrownBy(() -> createFullyConfiguredMailerBuilder(false, "", null).buildMailer()
				.sendMail(EmailHelper.createDummyEmailBuilder(true, false, false, true, false, false)
						.clearAttachments()
						.withAttachment("sweety tooth", new ByteArrayDataSource("moomoo".getBytes(), "text/plain"), "evil\ndescription")
						.buildEmail()))
				.isInstanceOf(MailSuspiciousCRLFValueException.class)
				.hasMessage("Suspected of injection attack, field: email.attachment.description with suspicious value: evil\ndescription");
	}

	@Test
	public void testCustomMailer_sendEmail_failOn_headerName() {
		assertThatThrownBy(() -> createFullyConfiguredMailerBuilder(false, "", null).buildMailer()
				.sendMail(EmailHelper.createDummyEmailBuilder(true, false, false, true, false, false)
						.withHeader("bad%0Aname", "good value")
						.buildEmail()))
				.isInstanceOf(MailSuspiciousCRLFValueException.class)
				.hasMessage("Suspected of injection attack, field: email.header.headerName with suspicious value: bad%0Aname");
	}

	@Test
	public void testCustomMailer_sendEmail_failOn_headerValue() {
		assertThatThrownBy(() -> createFullyConfiguredMailerBuilder(false, "", null).buildMailer()
				.sendMail(EmailHelper.createDummyEmailBuilder(true, false, false, true, false, false)
						.withHeader("good name", "bad\rvalue")
						.buildEmail()))
				.isInstanceOf(MailSuspiciousCRLFValueException.class)
				.hasMessage("Suspected of injection attack, field: email.header.[good name] with suspicious value: bad\rvalue");
	}
}