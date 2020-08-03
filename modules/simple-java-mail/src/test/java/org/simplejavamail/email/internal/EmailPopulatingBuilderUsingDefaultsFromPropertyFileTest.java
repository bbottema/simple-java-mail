package org.simplejavamail.email.internal;

import org.jetbrains.annotations.NotNull;
import org.junit.Before;
import org.junit.Test;
import org.simplejavamail.api.email.Email;
import org.simplejavamail.config.ConfigLoader;
import org.simplejavamail.config.ConfigLoader.Property;
import org.simplejavamail.email.EmailBuilder;
import testutil.ConfigLoaderTestHelper;
import testutil.EmailHelper;

import javax.mail.util.ByteArrayDataSource;
import java.io.IOException;
import java.nio.charset.Charset;
import java.util.Properties;

import static demo.ResourceFolderHelper.determineResourceFolder;
import static javax.xml.bind.DatatypeConverter.parseBase64Binary;
import static org.assertj.core.api.Assertions.assertThat;

public class EmailPopulatingBuilderUsingDefaultsFromPropertyFileTest {

	@Before
	// normally not needed, but IntelliJ test coverage doesn't work with JVM forking (which normally solves static mutation issues)
	public void setup() {
		ConfigLoaderTestHelper.restoreOriginalConfigProperties();
	}

	@Test
	public void testBuilderSimpleBuildWithStandardEmail()
			throws IOException {
		ByteArrayDataSource namedAttachment = new ByteArrayDataSource("Black Tie Optional", "text/plain");
		namedAttachment.setName("dresscode-ignored-because-of-override.txt");
		String base64String = "iVBORw0KGgoAAAANSUhEUgAAACAAAAAgCAYAAABzenr0AAABeElEQVRYw2NgoAAYGxu3GxkZ7TY1NZVloDcAWq4MxH+B+D8Qv3FwcOCgtwM6oJaDMTAUXOhmuYqKCjvQ0pdoDrCnmwNMTEwakC0H4u8GBgYC9Ap6DSD+iewAoIPm0ctyLqBlp9F8/x+YE4zpYT8T0LL16JYD8U26+B7oyz4sloPwenpYno3DchCeROsUbwa05A8eB3wB4kqgIxOAuArIng7EW4H4EhC/B+JXQLwDaI4ryZaDSjeg5mt4LCcFXyIn1fdSyXJQVt1OtMWGhoai0OD8T0W8GohZifE1PxD/o7LlsPLiFNAKRrwOABWptLAcqc6QGDAHQEOAYaAc8BNotsJAOgAUAosG1AFA/AtUoY3YEFhKMAvS2AE7iC1+WaG1H6gY3gzE36hUFJ8mqzbU1dUVBBqQBzTgIDQRkWo5qCZdpaenJ0Zx1aytrc0DDB0foIG1oAYKqC0IZK8D4n1AfA6IzwPxXpCFoGoZVEUDaRGGUTAKRgEeAAA2eGJC+ETCiAAAAABJRU5ErkJggg==";

		fixLoadedPropertyPath(Property.SMIME_ENCRYPTION_CERTIFICATE);

		final Email email = EmailBuilder.startingBlank()
				.from("lollypop", "lol.pop@somemail.com")
				.to("C.Cane", "candycane@candyshop.org")
				.withPlainText("We should meet up!")
				.withHTMLText("<b>We should meet up!</b><img src='cid:thumbsup'>")
				.withSubject("hey")
				.withAttachment("dresscode.txt", namedAttachment)
				.withAttachment("location.txt", "On the moon!".getBytes(Charset.defaultCharset()), "text/plain")
				.withEmbeddedImage("thumbsup", parseBase64Binary(base64String), "image/png")
				.buildEmail();
		
		assertThat(EmailHelper.createDummyEmailBuilder(true, true, false, false, false, false).buildEmail()).isEqualTo(email);
	}

	@SuppressWarnings("SameParameterValue")
	private void fixLoadedPropertyPath(@NotNull final Property property) {
		final String originalProperty = ConfigLoader.getStringProperty(property);
		final Properties properties = new Properties();
		properties.put(property.key(), determineResourceFolder("simple-java-mail").replace("src", "") + originalProperty);
		ConfigLoader.loadProperties(properties, false);
	}

	@Test
	public void testBuilderSimpleBuildWithStandardEmail_PlusOptionals()
			throws IOException {
		ByteArrayDataSource namedAttachment = new ByteArrayDataSource("Black Tie Optional", "text/plain");
		namedAttachment.setName("dresscode-ignored-because-of-override.txt");
		String base64String = "iVBORw0KGgoAAAANSUhEUgAAACAAAAAgCAYAAABzenr0AAABeElEQVRYw2NgoAAYGxu3GxkZ7TY1NZVloDcAWq4MxH+B+D8Qv3FwcOCgtwM6oJaDMTAUXOhmuYqKCjvQ0pdoDrCnmwNMTEwakC0H4u8GBgYC9Ap6DSD+iewAoIPm0ctyLqBlp9F8/x+YE4zpYT8T0LL16JYD8U26+B7oyz4sloPwenpYno3DchCeROsUbwa05A8eB3wB4kqgIxOAuArIng7EW4H4EhC/B+JXQLwDaI4ryZaDSjeg5mt4LCcFXyIn1fdSyXJQVt1OtMWGhoai0OD8T0W8GohZifE1PxD/o7LlsPLiFNAKRrwOABWptLAcqc6QGDAHQEOAYaAc8BNotsJAOgAUAosG1AFA/AtUoY3YEFhKMAvS2AE7iC1+WaG1H6gY3gzE36hUFJ8mqzbU1dUVBBqQBzTgIDQRkWo5qCZdpaenJ0Zx1aytrc0DDB0foIG1oAYKqC0IZK8D4n1AfA6IzwPxXpCFoGoZVEUDaRGGUTAKRgEeAAA2eGJC+ETCiAAAAABJRU5ErkJggg==";
		
		final Email email = EmailBuilder.startingBlank()
				.from("lollypop", "lol.pop@somemail.com")
				.withReplyTo("lollypop-reply", "lol.pop.reply@somemail.com")
				.withBounceTo("lollypop-bounce", "lol.pop.bounce@somemail.com")
				.to("C.Cane", "candycane@candyshop.org")
				.withPlainText("We should meet up!")
				.withHTMLText("<b>We should meet up!</b><img src='cid:thumbsup'>")
				.withSubject("hey")
				.withAttachment("dresscode.txt", namedAttachment)
				.withAttachment("location.txt", "On the moon!".getBytes(Charset.defaultCharset()), "text/plain")
				.withEmbeddedImage("thumbsup", parseBase64Binary(base64String), "image/png")
				.withDispositionNotificationTo("simple@address.com")
				.withReturnReceiptTo("Complex Email", "simple@address.com")
				.withHeader("dummyHeader", "dummyHeaderValue")
				.withHeader("anotherDummyHeader", "anotherDummyHeaderValue")
				.buildEmail();
		
		assertThat(EmailHelper.createDummyEmailBuilder(true, false, true, false, false, false).buildEmail()).isEqualTo(email);
	}
}