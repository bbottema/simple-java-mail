package org.simplejavamail.converter.internal.mimemessage;

import org.assertj.core.api.ThrowableAssert;
import org.junit.Test;
import org.simplejavamail.converter.EmailConverter;
import org.simplejavamail.converter.internal.mimemessage.MimeMessageParser.ParsedMimeMessageComponents;
import org.simplejavamail.email.Email;
import org.simplejavamail.email.EmailAssert;
import org.simplejavamail.email.EmailBuilder;
import org.simplejavamail.email.Recipient;
import testutil.ConfigLoaderTestHelper;

import javax.mail.internet.AddressException;
import javax.mail.internet.InternetAddress;
import javax.mail.util.ByteArrayDataSource;
import java.io.IOException;
import java.io.UnsupportedEncodingException;

import static javax.mail.Message.RecipientType.TO;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
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
		parsedComponents.htmlContent = "blah moo1 blah html";
		parsedComponents.cidMap.put("moo1", new ByteArrayDataSource("moomoo", "text/plain"));
		parsedComponents.cidMap.put("moo2", new ByteArrayDataSource("moomoo", "text/plain"));
		moveInvalidEmbeddedResourcesToAttachments(parsedComponents);
		
		assertThat(parsedComponents.cidMap).isEmpty();
		assertThat(parsedComponents.attachmentList).extracting("key").containsOnly("moo1", "moo2");
	}
	
	@Test
	public void testMoveInvalidEmbeddedResourcesToAttachments_Invalid() throws IOException {
		ParsedMimeMessageComponents parsedComponents = new ParsedMimeMessageComponents();
		parsedComponents.htmlContent = "blah cid:moo1 blah html";
		parsedComponents.cidMap.put("moo1", new ByteArrayDataSource("moomoo", "text/plain"));
		parsedComponents.cidMap.put("moo2", new ByteArrayDataSource("moomoo", "text/plain"));
		moveInvalidEmbeddedResourcesToAttachments(parsedComponents);
		
		assertThat(parsedComponents.cidMap).containsOnlyKeys("moo1");
		assertThat(parsedComponents.attachmentList).extracting("key").containsOnly("moo2");
	}
	
	@Test
	public void testCreateAddress() throws AddressException, UnsupportedEncodingException {
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
		
		// next one is unparsable by InternetAddress#parse(), so it should be taken as is
		assertThatThrownBy(new ThrowableAssert.ThrowingCallable() {
			@Override
			public void call() throws Throwable {
				interpretRecipient(" \"  m oo  \" a@b.com    ");
			}
		})
				.isInstanceOf(MimeMessageParseException.class)
				.hasMessage("Error parsing [TO] address [ \"  m oo  \" a@b.com    ]");
	}
	
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