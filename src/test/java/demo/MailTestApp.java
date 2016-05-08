package demo;

import static javax.xml.bind.DatatypeConverter.parseBase64Binary;

import javax.mail.Message.RecipientType;
import javax.mail.MessagingException;
import javax.mail.Session;
import javax.mail.internet.MimeMessage;
import javax.mail.util.ByteArrayDataSource;
import java.io.IOException;
import java.nio.charset.Charset;
import java.util.Properties;

import org.codemonkey.simplejavamail.Mailer;
import org.codemonkey.simplejavamail.TransportStrategy;
import org.codemonkey.simplejavamail.email.Email;

/**
 * Demonstration program for the Simple Java Mail framework.
 *
 * @author Benny Bottema
 */
public class MailTestApp {

    public static void main(final String[] args) throws IOException, MessagingException {
        final Email emailNormal = new Email();
        emailNormal.setFromAddress("lollypop", "lol.pop@somemail.com");
        // don't forget to add your own address here ->
        emailNormal.addRecipient("C.Cane", "candycane@candyshop.org", RecipientType.TO);
        emailNormal.setText("We should meet up!");
        emailNormal.setTextHTML("<b>We should meet up!</b><img src='cid:thumbsup'>");
        emailNormal.setSubject("hey");

        // add two text files in different ways and a black thumbs up embedded image ->
        emailNormal.addAttachment("dresscode.txt", new ByteArrayDataSource("Black Tie Optional", "text/plain"));
        emailNormal.addAttachment("location.txt", "On the moon!".getBytes(Charset.defaultCharset()), "text/plain");
        String base64String = "iVBORw0KGgoAAAANSUhEUgAAACAAAAAgCAYAAABzenr0AAABeElEQVRYw2NgoAAYGxu3GxkZ7TY1NZVloDcAWq4MxH+B+D8Qv3FwcOCgtwM6oJaDMTAUXOhmuYqKCjvQ0pdoDrCnmwNMTEwakC0H4u8GBgYC9Ap6DSD+iewAoIPm0ctyLqBlp9F8/x+YE4zpYT8T0LL16JYD8U26+B7oyz4sloPwenpYno3DchCeROsUbwa05A8eB3wB4kqgIxOAuArIng7EW4H4EhC/B+JXQLwDaI4ryZaDSjeg5mt4LCcFXyIn1fdSyXJQVt1OtMWGhoai0OD8T0W8GohZifE1PxD/o7LlsPLiFNAKRrwOABWptLAcqc6QGDAHQEOAYaAc8BNotsJAOgAUAosG1AFA/AtUoY3YEFhKMAvS2AE7iC1+WaG1H6gY3gzE36hUFJ8mqzbU1dUVBBqQBzTgIDQRkWo5qCZdpaenJ0Zx1aytrc0DDB0foIG1oAYKqC0IZK8D4n1AfA6IzwPxXpCFoGoZVEUDaRGGUTAKRgEeAAA2eGJC+ETCiAAAAABJRU5ErkJggg==";
        emailNormal.addEmbeddedImage("thumbsup", parseBase64Binary(base64String), "image/png");

        // let's try producing and then consuming a MimeMessage ->
        final MimeMessage mimeMessage = Mailer.produceMimeMessage(emailNormal, Session.getDefaultInstance(new Properties()));
        final Email emailFromMimeMessage = new Email(mimeMessage);

        sendMail(emailNormal);
        sendMail(emailFromMimeMessage); // should produce the exact same result as emailNormal!
    }

    private static void sendMail(final Email email) {
        final String host = System.getProperty("host") != null ? System.getProperty("host") : "";
        final int port = System.getProperty("port") != null ? Integer.parseInt(System.getProperty("port")) : 25;
        final String username = System.getProperty("username") != null ? System.getProperty("username") : "";
        final String password = System.getProperty("password") != null ? System.getProperty("password") : "";
        new Mailer(host, port, username, password, TransportStrategy.SMTP_SSL).sendMail(email);
    }
}