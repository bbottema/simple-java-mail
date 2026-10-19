package org.simplejavamail.email.internal;

import lombok.val;
import org.junit.jupiter.api.Test;
import jakarta.activation.DataSource;
import org.simplejavamail.api.email.AttachmentResource;
import org.simplejavamail.api.email.CalendarMethod;
import org.simplejavamail.api.email.Email;
import org.simplejavamail.api.email.EmailAssert;
import org.simplejavamail.api.email.EmailPopulatingBuilder;
import org.simplejavamail.api.email.config.DeliveryStatusNotification;
import org.simplejavamail.api.email.config.DkimConfig;
import org.simplejavamail.converter.EmailConverter;
import org.simplejavamail.api.SimpleJavaMail;
import testutil.ConfigLoaderTestHelper;
import testutil.EmailHelper;

import java.io.File;
import java.io.IOException;
import java.util.Date;
import java.util.List;
import java.util.Random;

import static demo.ResourceFolderHelper.determineResourceFolder;
import static jakarta.mail.Message.RecipientType.BCC;
import static jakarta.mail.Message.RecipientType.CC;
import static jakarta.mail.Message.RecipientType.TO;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.simplejavamail.api.email.config.DeliveryStatusNotification.NotifyOption.DELAY;
import static org.simplejavamail.api.email.config.DeliveryStatusNotification.NotifyOption.FAILURE;
import static org.simplejavamail.api.email.config.DeliveryStatusNotification.ReturnOption.HEADERS_ONLY;

public class EmailStartingBuilderImplTest {

	private static final String RESOURCES_PATH = determineResourceFolder("simple-java-mail") + "/test/resources";
	private static final String RESOURCES_TEST_MESSAGES = RESOURCES_PATH + "/test-messages";

	@SuppressWarnings("deprecation")
	@Test
	public void testCopying() {
		val email1 = EmailHelper.createDummyEmailBuilder("moo", true, false, true, true, true, false, true)
				.signWithDomainKey(DkimConfig.builder()
						.dkimPrivateKeyData("dummykey")
						.dkimSigningDomain("moo.com")
						.dkimSelector("selector")
						.build())
				.buildEmailCompletedWithDefaultsAndOverrides();

		((InternalEmail) email1).setUserProvidedEmail(null);

		val email2 = SimpleJavaMail.withConfig(ConfigLoaderTestHelper.emptyConfig()).emailBuilder()
				.copying(email1)
				.ignoringDefaults()
				.ignoringOverrides()
				.buildEmail();

		assertThat(email2).isEqualTo(email1);
	}

	@Test
	public void testCopyingSmimeSignedOutlookMessage() {
		final Email emailParsedFromMsg = EmailConverter.outlookMsgToEmail(new File(RESOURCES_TEST_MESSAGES + "/SMIME (signed and clear text).msg"));
		final EmailPopulatingBuilder copyingEmailBuilder = SimpleJavaMail.withConfig(ConfigLoaderTestHelper.emptyConfig()).emailBuilder().copying(emailParsedFromMsg).ignoringDefaults();
		assertThat(copyingEmailBuilder.getHeaders()).isEmpty(); // when copying S/MIME generated message id should be ignored
		copyingEmailBuilder.withHeaders(emailParsedFromMsg.getHeaders()); // but for the equals check, manually add them
		EmailAssert.assertThat(copyingEmailBuilder.buildEmail()).isEqualTo(emailParsedFromMsg);
	}

	@Test
	public void copyingMergedSmimeResourcesPreservesOccurrencesWithoutReadingContent() throws Exception {
		final SimpleJavaMail mail = SimpleJavaMail.withConfig(ConfigLoaderTestHelper.emptyConfig());
		final DataSource source = mock(DataSource.class);
		when(source.getInputStream()).thenThrow(new IOException("Borrowed content must not be consumed while copying"));
		final AttachmentResource resource = new AttachmentResource("shared.txt", source, null, null);
		final InternalEmailPopulatingBuilder signed = (InternalEmailPopulatingBuilder) mail.emailBuilder().startingBlank();
		signed.withAttachments(List.of(resource)).withEmbeddedImages(List.of(resource)).withPlainText("signed body");
		signed.withDecryptedAttachments(List.of(resource));
		final InternalEmailPopulatingBuilder outer = (InternalEmailPopulatingBuilder) mail.emailBuilder().startingBlank();
		outer.withAttachments(List.of(resource, resource)).withEmbeddedImages(List.of(resource, resource));
		outer.withDecryptedAttachments(List.of(resource, resource));
		outer.withSmimeSignedEmail(signed.buildEmail());
		final Email original = outer.buildEmail();
		Email copy = original;

		for (int i = 0; i < 3; i++) {
			copy = mail.emailBuilder().copying(copy).buildEmail();
			assertThat(copy.getAttachments()).containsExactly(resource, resource, resource);
			assertThat(copy.getEmbeddedImages()).containsExactly(resource, resource, resource);
			assertThat(copy.getDecryptedAttachments()).containsExactly(resource, resource, resource);
			assertThat(copy.getPlainText()).isEqualTo("signed body");
		}
		assertThat(original.getAttachments()).containsExactly(resource, resource, resource);
		verify(source, never()).getInputStream();
	}

	@Test
	public void testCopyingCompleteAndEquals() {

		Email fullyPopulatedEmail = SimpleJavaMail.withConfig(ConfigLoaderTestHelper.emptyConfig()).emailBuilder()
				.startingBlank()
				.withRecipients(EmailHelper.parsedRecipients("mr moo to", true, TO, "mr@moo.com"))
				.withRecipients(EmailHelper.parsedRecipients("mr moo cc", true, CC, "mr@moo.com"))
				.withRecipients(EmailHelper.parsedRecipients("mr moo bcc", true, BCC, "mr@moo.com"))
				.withAttachment("mooxt", "attachment content".getBytes(), "text/plain")
				.withEmbeddedImage("mooxt", "attachment content".getBytes(), "text/plain")
				.withBounceTo("bounce@bouncy.com")
				.withDeliveryStatusNotification(HEADERS_ONLY, FAILURE, DELAY)
				.withDispositionNotificationTo("noti@notify.com")
				.withHeader("header 1", "value 1")
				.withHeader("header 2", new Random())
				.withPlainText("blahblah plain text")
				.withHTMLText("blahblah dynamically embedded image <img src='/test-dynamicembedded-image/excellent.png'>")
				.withCalendarText(CalendarMethod.COUNTER, "Calendar text")
				.withReplyTo("reply@to.com")
				.withSubject("mooject")
				.withReturnReceiptTo("RECEIPT@moo.com")
				.signWithDomainKey(DkimConfig.builder()
						.dkimPrivateKeyData("dkim_key")
						.dkimSigningDomain("dkim_domain")
						.dkimSelector("dkim_selector")
						.build())
				.from("it's a me! <mario@moo.com>")
				.fixingMessageId("id123")
				.fixingSentDate(new Date())
				.notMergingSingleSMIMESignedAttachment()
				.notMergingSingleSMIMESignedAttachment()
				// auto resolution for embedded images
				.withEmbeddedImageBaseDir("moo")
				.withEmbeddedImageBaseUrl("http://moo.moomoo.shmoo")
				.withEmbeddedImageBaseClassPath("moo")
				.withEmbeddedImageAutoResolutionForClassPathResources(true)
				.withEmbeddedImageAutoResolutionForURLs(true)
				.withEmbeddedImageAutoResolutionForFiles(true)
				.allowingEmbeddedImageOutsideBaseClassPath(true)
				.allowingEmbeddedImageOutsideBaseDir(true)
				.allowingEmbeddedImageOutsideBaseUrl(true)
				.embeddedImageAutoResolutionMustBeSuccesful(true)
				.buildEmail();

		Email copiedEmail = SimpleJavaMail.withConfig(ConfigLoaderTestHelper.emptyConfig()).emailBuilder()
				.copying(fullyPopulatedEmail)
				.buildEmail();

		EmailAssert.assertThat(copiedEmail).isEqualTo(fullyPopulatedEmail);
		assertThat(copiedEmail).isEqualToComparingFieldByFieldRecursively(fullyPopulatedEmail);
		assertThat(copiedEmail.getDeliveryStatusNotification())
				.isEqualTo(DeliveryStatusNotification.of(HEADERS_ONLY, FAILURE, DELAY));
	}
}
