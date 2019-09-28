package org.simplejavamail.converter.internal.mimemessage;

import org.junit.Test;
import org.simplejavamail.api.email.Email;
import org.simplejavamail.api.email.EmailAssert;
import org.simplejavamail.api.email.Recipient;
import org.simplejavamail.converter.EmailConverter;
import org.simplejavamail.converter.internal.mimemessage.MimeMessageParser.ParsedMimeMessageComponents;
import org.simplejavamail.email.EmailBuilder;
import testutil.ConfigLoaderTestHelper;

import javax.mail.util.ByteArrayDataSource;
import java.io.IOException;

import static javax.mail.Message.RecipientType.TO;
import static org.assertj.core.api.Assertions.assertThat;
import static org.simplejavamail.converter.internal.mimemessage.MimeMessageParser.moveInvalidEmbeddedResourcesToAttachments;

public class MimeMessageParserTest {

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