package org.simplejavamail.email;

import org.junit.Test;
import testutil.EmailHelper;

import javax.mail.util.ByteArrayDataSource;
import java.io.IOException;
import java.nio.charset.Charset;

import static javax.xml.bind.DatatypeConverter.parseBase64Binary;
import static org.assertj.core.api.Assertions.assertThat;

public class EmailBuilderUsingDefaultsFromPropertyFileTest {
	
	@Test
	public void testBuilderSimpleBuildWithStandardEmail()
			throws IOException {
		ByteArrayDataSource namedAttachment = new ByteArrayDataSource("Black Tie Optional", "text/plain");
		namedAttachment.setName("dresscode.txt"); // normally not needed, but otherwise the equals will fail
		String base64String = "iVBORw0KGgoAAAANSUhEUgAAACAAAAAgCAYAAABzenr0AAABeElEQVRYw2NgoAAYGxu3GxkZ7TY1NZVloDcAWq4MxH+B+D8Qv3FwcOCgtwM6oJaDMTAUXOhmuYqKCjvQ0pdoDrCnmwNMTEwakC0H4u8GBgYC9Ap6DSD+iewAoIPm0ctyLqBlp9F8/x+YE4zpYT8T0LL16JYD8U26+B7oyz4sloPwenpYno3DchCeROsUbwa05A8eB3wB4kqgIxOAuArIng7EW4H4EhC/B+JXQLwDaI4ryZaDSjeg5mt4LCcFXyIn1fdSyXJQVt1OtMWGhoai0OD8T0W8GohZifE1PxD/o7LlsPLiFNAKRrwOABWptLAcqc6QGDAHQEOAYaAc8BNotsJAOgAUAosG1AFA/AtUoY3YEFhKMAvS2AE7iC1+WaG1H6gY3gzE36hUFJ8mqzbU1dUVBBqQBzTgIDQRkWo5qCZdpaenJ0Zx1aytrc0DDB0foIG1oAYKqC0IZK8D4n1AfA6IzwPxXpCFoGoZVEUDaRGGUTAKRgEeAAA2eGJC+ETCiAAAAABJRU5ErkJggg==";
		
		final Email email = new EmailBuilder()
				.from("lollypop", "lol.pop@somemail.com")
				.to("C.Cane", "candycane@candyshop.org")
				.text("We should meet up!")
				.textHTML("<b>We should meet up!</b><img src='cid:thumbsup'>")
				.subject("hey")
				.addAttachment("dresscode.txt", namedAttachment)
				.addAttachment("location.txt", "On the moon!".getBytes(Charset.defaultCharset()), "text/plain")
				.embedImage("thumbsup", parseBase64Binary(base64String), "image/png")
				.build();
		
		assertThat(EmailHelper.createDummyEmail(true, true, false)).isEqualTo(email);
	}
	
	@Test
	public void testBuilderSimpleBuildWithStandardEmail_PlusOptionals()
			throws IOException {
		ByteArrayDataSource namedAttachment = new ByteArrayDataSource("Black Tie Optional", "text/plain");
		namedAttachment.setName("dresscode.txt"); // normally not needed, but otherwise the equals will fail
		String base64String = "iVBORw0KGgoAAAANSUhEUgAAACAAAAAgCAYAAABzenr0AAABeElEQVRYw2NgoAAYGxu3GxkZ7TY1NZVloDcAWq4MxH+B+D8Qv3FwcOCgtwM6oJaDMTAUXOhmuYqKCjvQ0pdoDrCnmwNMTEwakC0H4u8GBgYC9Ap6DSD+iewAoIPm0ctyLqBlp9F8/x+YE4zpYT8T0LL16JYD8U26+B7oyz4sloPwenpYno3DchCeROsUbwa05A8eB3wB4kqgIxOAuArIng7EW4H4EhC/B+JXQLwDaI4ryZaDSjeg5mt4LCcFXyIn1fdSyXJQVt1OtMWGhoai0OD8T0W8GohZifE1PxD/o7LlsPLiFNAKRrwOABWptLAcqc6QGDAHQEOAYaAc8BNotsJAOgAUAosG1AFA/AtUoY3YEFhKMAvS2AE7iC1+WaG1H6gY3gzE36hUFJ8mqzbU1dUVBBqQBzTgIDQRkWo5qCZdpaenJ0Zx1aytrc0DDB0foIG1oAYKqC0IZK8D4n1AfA6IzwPxXpCFoGoZVEUDaRGGUTAKRgEeAAA2eGJC+ETCiAAAAABJRU5ErkJggg==";
		
		final Email email = new EmailBuilder()
				.from("lollypop", "lol.pop@somemail.com")
				.replyTo("lollypop-reply", "lol.pop.reply@somemail.com")
				.to("C.Cane", "candycane@candyshop.org")
				.text("We should meet up!")
				.textHTML("<b>We should meet up!</b><img src='cid:thumbsup'>")
				.subject("hey")
				.addAttachment("dresscode.txt", namedAttachment)
				.addAttachment("location.txt", "On the moon!".getBytes(Charset.defaultCharset()), "text/plain")
				.embedImage("thumbsup", parseBase64Binary(base64String), "image/png")
				.withDispositionNotificationTo("simple@address.com")
				.withReturnReceiptTo("Complex Email", "simple@address.com")
				.addHeader("dummyHeader", "dummyHeaderValue")
				.build();
		
		assertThat(EmailHelper.createDummyEmail(true, false, true)).isEqualTo(email);
	}
}