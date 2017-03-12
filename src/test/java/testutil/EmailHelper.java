package testutil;

import org.simplejavamail.email.Email;

import javax.mail.util.ByteArrayDataSource;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.Charset;

import static javax.mail.Message.RecipientType.TO;
import static javax.xml.bind.DatatypeConverter.parseBase64Binary;
import static org.simplejavamail.converter.EmailConverter.outlookMsgToEmail;

public class EmailHelper {

	public static Email createDummyEmail()
			throws IOException {
		final Email emailNormal = new Email();
		emailNormal.setFromAddress("lollypop", "lol.pop@somemail.com");
		// normally not needed, but for the test it is because the MimeMessage will
		// have it added automatically as well, so the parsed Email will also have it then
		emailNormal.setReplyToAddress("lollypop", "lol.pop@somemail.com");
		// don't forget to add your own address here ->
		emailNormal.addRecipient("C.Cane", "candycane@candyshop.org", TO);
		emailNormal.setText("We should meet up!");
		emailNormal.setTextHTML("<b>We should meet up!</b><img src='cid:thumbsup'>");
		emailNormal.setSubject("hey");

		// add two text files in different ways and a black thumbs up embedded image ->
		ByteArrayDataSource namedAttachment = new ByteArrayDataSource("Black Tie Optional", "text/plain");
		namedAttachment.setName("dresscode.txt"); // normally not needed, but otherwise the equals will fail
		emailNormal.addAttachment("dresscode.txt", namedAttachment);
		emailNormal.addAttachment("location.txt", "On the moon!".getBytes(Charset.defaultCharset()), "text/plain");
		String base64String = "iVBORw0KGgoAAAANSUhEUgAAACAAAAAgCAYAAABzenr0AAABeElEQVRYw2NgoAAYGxu3GxkZ7TY1NZVloDcAWq4MxH+B+D8Qv3FwcOCgtwM6oJaDMTAUXOhmuYqKCjvQ0pdoDrCnmwNMTEwakC0H4u8GBgYC9Ap6DSD+iewAoIPm0ctyLqBlp9F8/x+YE4zpYT8T0LL16JYD8U26+B7oyz4sloPwenpYno3DchCeROsUbwa05A8eB3wB4kqgIxOAuArIng7EW4H4EhC/B+JXQLwDaI4ryZaDSjeg5mt4LCcFXyIn1fdSyXJQVt1OtMWGhoai0OD8T0W8GohZifE1PxD/o7LlsPLiFNAKRrwOABWptLAcqc6QGDAHQEOAYaAc8BNotsJAOgAUAosG1AFA/AtUoY3YEFhKMAvS2AE7iC1+WaG1H6gY3gzE36hUFJ8mqzbU1dUVBBqQBzTgIDQRkWo5qCZdpaenJ0Zx1aytrc0DDB0foIG1oAYKqC0IZK8D4n1AfA6IzwPxXpCFoGoZVEUDaRGGUTAKRgEeAAA2eGJC+ETCiAAAAABJRU5ErkJggg==";
		emailNormal.addEmbeddedImage("thumbsup", parseBase64Binary(base64String), "image/png");
		return emailNormal;
	}

	public static Email readOutlookMessage(final String filePath) {
		InputStream resourceAsStream = EmailHelper.class.getClassLoader().getResourceAsStream(filePath);
		return outlookMsgToEmail(resourceAsStream);
	}

	public static String normalizeText(String text) {
		return text.replaceAll("\\r\\n", "\n").replaceAll("\\r", "\n");
	}
}
