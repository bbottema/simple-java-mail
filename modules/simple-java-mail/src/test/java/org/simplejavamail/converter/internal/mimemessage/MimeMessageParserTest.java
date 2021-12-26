package org.simplejavamail.converter.internal.mimemessage;

import jakarta.activation.DataHandler;
import jakarta.mail.BodyPart;
import jakarta.mail.MessagingException;
import jakarta.mail.Part;
import jakarta.mail.Session;
import jakarta.mail.internet.InternetAddress;
import jakarta.mail.internet.MimeBodyPart;
import jakarta.mail.internet.MimeMessage;
import jakarta.mail.internet.MimeMultipart;
import jakarta.mail.internet.ParameterList;
import jakarta.mail.util.ByteArrayDataSource;
import org.assertj.core.api.ThrowableAssert;
import org.jetbrains.annotations.Nullable;
import org.junit.Before;
import org.junit.Test;
import org.simplejavamail.api.email.AttachmentResource;
import org.simplejavamail.api.email.Email;
import org.simplejavamail.api.email.EmailAssert;
import org.simplejavamail.api.email.Recipient;
import org.simplejavamail.converter.EmailConverter;
import org.simplejavamail.converter.internal.mimemessage.MimeMessageParser.ParsedMimeMessageComponents;
import org.simplejavamail.email.EmailBuilder;
import org.simplejavamail.internal.util.NamedDataSource;
import testutil.ConfigLoaderTestHelper;
import testutil.EmailHelper;

import java.io.IOException;
import java.io.UnsupportedEncodingException;
import java.util.Calendar;
import java.util.Date;
import java.util.GregorianCalendar;
import java.util.Properties;

import static java.lang.String.format;
import static javax.mail.Message.RecipientType.TO;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.simplejavamail.converter.internal.mimemessage.MimeMessageParser.moveInvalidEmbeddedResourcesToAttachments;

public class MimeMessageParserTest {

	@Before
	public void setup() {
		ConfigLoaderTestHelper.clearConfigProperties();
	}

	@Test
	public void testBasicParsing()
			throws IOException {
		Email originalEmail = EmailHelper.createDummyEmailBuilder(true, true, false, true, false, false).buildEmail();

		MimeMessage mimeMessage = EmailConverter.emailToMimeMessage(originalEmail);
		ParsedMimeMessageComponents mimeMessageParts = MimeMessageParser.parseMimeMessage(mimeMessage);

		assertThat(mimeMessageParts.getMessageId()).isNull();

		assertThat(mimeMessageParts.getFromAddress().getPersonal()).isEqualTo(originalEmail.getFromRecipient().getName());
		assertThat(mimeMessageParts.getFromAddress().getAddress()).isEqualTo(originalEmail.getFromRecipient().getAddress());
		assertThat(mimeMessageParts.getReplyToAddresses().getPersonal()).isEqualTo(originalEmail.getFromRecipient().getName());
		assertThat(mimeMessageParts.getReplyToAddresses().getAddress()).isEqualTo(originalEmail.getFromRecipient().getAddress());

		GregorianCalendar receiveWindowStart = new GregorianCalendar();
		receiveWindowStart.add(Calendar.SECOND, -5);
		assertThat(mimeMessageParts.getSentDate()).isBetween(receiveWindowStart.getTime(), new Date());

		assertThat(mimeMessageParts.getCidMap()).containsOnlyKeys("<thumbsup>");
		assertThat(mimeMessageParts.getAttachmentList()).extracting("key").containsOnly("dresscode.txt", "location.txt");
	}

	@Test
	public void testAttachmentNameResolution()
			throws MessagingException, IOException {
		MimeMessage mimeMessage = produceMimeMessageWithNamingIssue();
		ParsedMimeMessageComponents components = MimeMessageParser.parseMimeMessage(mimeMessage);

		assertThat(components.getHtmlContent()).isNull();
		assertThat(components.getPlainContent()).isEqualTo("body text");
		assertThat(components.getCidMap()).isEmpty();
		assertThat(components.getAttachmentList()).extracting("key").containsOnly("proper-name.txt");
	}

	private MimeMessage produceMimeMessageWithNamingIssue()
			throws MessagingException, IOException {
		MimeMessage m = new MimeMessage(Session.getDefaultInstance(new Properties()));
		MimeMultipart multipartRootMixed = new MimeMultipart("mixed");

		// content
		final MimeBodyPart messagePart = new MimeBodyPart();
		messagePart.setText("body text", "UTF-8");
		multipartRootMixed.addBodyPart(messagePart);

		// attachments
		final AttachmentResource r = new AttachmentResource("wrong-name.txt", new ByteArrayDataSource("Black Tie Optional", "text/plain"));
		multipartRootMixed.addBodyPart(getBodyPartFromDatasource(r, Part.ATTACHMENT));

		m.setContent(multipartRootMixed);

		return m;
	}

	private static BodyPart getBodyPartFromDatasource(final AttachmentResource attachmentResource, final String dispositionType)
			throws MessagingException {
		final BodyPart attachmentPart = new MimeBodyPart();
		// setting headers isn't working nicely using the javax mail API, so let's do that manually
		final String resourceName = "htgfiytityf.txt";
		final String fileName = "proper-name.txt";
		attachmentPart.setDataHandler(new DataHandler(new NamedDataSource(fileName, attachmentResource.getDataSource())));
		attachmentPart.setFileName(fileName);
		final String contentType = attachmentResource.getDataSource().getContentType();
		ParameterList pl = new ParameterList();
		pl.set("filename", fileName);
		pl.set("name", fileName);
		attachmentPart.setHeader("Content-Type", contentType + pl.toString());
		attachmentPart.setHeader("Content-ID", format("<%s>", resourceName));
		attachmentPart.setDisposition(dispositionType);
		return attachmentPart;
	}

	@Test
	public void testMoveInvalidEmbeddedResourcesToAttachments_NoHtmlNoInvalid() throws IOException {
		ParsedMimeMessageComponents parsedComponents = new ParsedMimeMessageComponents();
		parsedComponents.cidMap.put("moo1", new ByteArrayDataSource("moomoo", "text/plain"));
		parsedComponents.cidMap.put("moo2", new ByteArrayDataSource("moomoo", "text/plain"));
		moveInvalidEmbeddedResourcesToAttachments(parsedComponents);
		
		assertThat(parsedComponents.cidMap).isEmpty();
		assertThat(parsedComponents.attachmentList).extracting("key").containsOnly("moo1", "moo2");
	}

	@Test
	public void testMoveInvalidEmbeddedResourcesToAttachments_HtmlButNoInvalid() throws IOException {
		ParsedMimeMessageComponents parsedComponents = new ParsedMimeMessageComponents();
		parsedComponents.htmlContent.append("blah moo1 blah html");
		parsedComponents.cidMap.put("moo1", new ByteArrayDataSource("moomoo", "text/plain"));
		parsedComponents.cidMap.put("moo2", new ByteArrayDataSource("moomoo", "text/plain"));
		moveInvalidEmbeddedResourcesToAttachments(parsedComponents);
		
		assertThat(parsedComponents.cidMap).isEmpty();
		assertThat(parsedComponents.attachmentList).extracting("key").containsOnly("moo1", "moo2");
	}
	
	@Test
	public void testMoveInvalidEmbeddedResourcesToAttachments_Invalid() throws IOException {
		ParsedMimeMessageComponents parsedComponents = new ParsedMimeMessageComponents();
		parsedComponents.htmlContent.append("blah cid:moo1 blah html");
		parsedComponents.cidMap.put("moo1", new ByteArrayDataSource("moomoo", "text/plain"));
		parsedComponents.cidMap.put("moo2", new ByteArrayDataSource("moomoo", "text/plain"));
		moveInvalidEmbeddedResourcesToAttachments(parsedComponents);
		
		assertThat(parsedComponents.cidMap).containsOnlyKeys("moo1");
		assertThat(parsedComponents.attachmentList).extracting("key").containsOnly("moo2");
	}
	
	@Test
	public void testCreateAddress() throws UnsupportedEncodingException {
		assertThat(interpretRecipient("a@b.com")).isEqualTo(new InternetAddress("a@b.com", null));
		assertThat(interpretRecipient(" a@b.com ")).isEqualTo(new InternetAddress("a@b.com", null));
		assertThat(interpretRecipient(" <a@b.com> ")).isEqualTo(new InternetAddress("a@b.com", null));
		assertThat(interpretRecipient(" < a@b.com > ")).isEqualTo(new InternetAddress("a@b.com", null));
		assertThat(interpretRecipient("moo <a@b.com>")).isEqualTo(new InternetAddress("a@b.com", "moo"));
		assertThat(interpretRecipient("moo<a@b.com>")).isEqualTo(new InternetAddress("a@b.com", "moo"));
		assertThat(interpretRecipient(" moo< a@b.com   > ")).isEqualTo(new InternetAddress("a@b.com", "moo"));
		assertThat(interpretRecipient("\"moo\" <a@b.com>")).isEqualTo(new InternetAddress("a@b.com", "moo"));
		assertThat(interpretRecipient("\"moo\"<a@b.com>")).isEqualTo(new InternetAddress("a@b.com", "moo"));
		assertThat(interpretRecipient(" \"moo\"< a@b.com   > ")).isEqualTo(new InternetAddress("a@b.com", "moo"));
		assertThat(interpretRecipient(" \"  m oo  \"< a@b.com   > ")).isEqualTo(new InternetAddress("a@b.com", "  m oo  "));
		assertThat(interpretRecipient("< >")).isNull();
		assertThat(interpretRecipient("")).isNull();
		assertThat(interpretRecipient(" ")).isNull();

		// next one is unparsable by InternetAddress#parse(), so it should be taken as is
		assertThatThrownBy(new ThrowableAssert.ThrowingCallable() {
			@Override
			public void call() {
				interpretRecipient(" \"  m oo  \" a@b.com    ");
			}
		})
				.isInstanceOf(MimeMessageParseException.class)
				.hasMessage("Error parsing [TO] address [ \"  m oo  \" a@b.com    ]");
	}

	@Nullable
	private InternetAddress interpretRecipient(String address) {
		return MimeMessageParser.createAddress(address, "TO");
	}
	
	@Test
	// https://github.com/bbottema/simple-java-mail/issues/227
	public void testSemiColonSeparatedToAddresses() {
		ConfigLoaderTestHelper.clearConfigProperties();

		final Email initialEmail = EmailBuilder.startingBlank()
				.from("lollypop", "lol.pop@somemail.com")
				.to("C.Cane", "candycane@candyshop.org")
				.withPlainText("We should meet up!")
				.buildEmail();

		String corruptedEML = EmailConverter.emailToEML(initialEmail).replace(
				"To: \"C.Cane\" <candycane@candyshop.org>",
				"To: \"C.Cane\" <candycane@candyshop.org>;");

		final Email fixedEmail = EmailConverter.emlToEmail(corruptedEML);

		EmailAssert.assertThat(fixedEmail).hasOnlyRecipients(new Recipient("C.Cane", "candycane@candyshop.org", TO));
	}
}