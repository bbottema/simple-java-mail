package org.codemonkey.simplejavamail;

import net.markenwerk.utils.data.fetcher.BufferedFetcher;
import org.codemonkey.simplejavamail.email.Email;
import org.codemonkey.simplejavamail.email.EmailTest;
import org.junit.Test;

import javax.mail.MessagingException;
import javax.mail.Session;
import javax.mail.internet.MimeMessage;
import javax.xml.bind.DatatypeConverter;
import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.util.Properties;

import static java.nio.file.Files.readAllBytes;
import static javax.xml.bind.DatatypeConverter.parseBase64Binary;
import static javax.xml.bind.DatatypeConverter.printBase64Binary;
import static org.junit.Assert.*;

public class MailerTest {

	@Test
	public void testDKIMPriming()
			throws IOException, MessagingException {
		final Email email = EmailTest.createDummyEmail();

		String privateDERkeyBase64 = "MIIEvgIBADANBgkqhkiG9w0BAQEFAASCBKgwggSkAgEAAoIBAQDH4E0Yvb7c0sLZjAtNS7UOmhzyB6Ml1q9UjsUTiwPJGC3B/kC2KZH3Y1TCe6XQ8tUpGOiaI"
				+ "WhodKGJfnoJQdbjhgDpIA/hT6XOoX1eHHj9tdpoUcq4AE/S2+YZq64ygvCovdaNG6kdihAJ4Yz3bDZW7hXDSOHNZxJQokjgwT9XUgZeFcgRmD/nwDqzDEkPD+H1HI2tN4zFhuyAvSJIN"
				+ "ayp6UWV4KLhLjt5wxyskaNDDTCBRzCCUHV7JxhziaKNl06AruQ7LzBIeWcG8mvW51hBFDh1Oldav6CvsHXMkh4FDsDXLcxuBlu/wvU5WzwPoq175tWEBFe/PGHcOpsYpBIrAgMBAAECg"
				+ "gEALE4E6OoOZ6Z0OIK5cp3IeX8h0Wht+pI29RhciPN0mFu6sNiqAXb/ewXSoPWFkOZy55Q04w1qtlS8Bd06MdkdR/vJMePAgWIFT+MuBfFrxFlOG3ed5uFy0DucKq6Kg3nQ3KaP62FpQ"
				+ "Dn9SiNr+uBKv0QxIMOEAeLKBYOjgeZ0IueQZC3ESUboWft+l4dUElSRGwznZj4qh3twaMCTfJMsMlSVz3YjSsNspuYxLAD7gZD1WYUjncnAaUPmdAQvWSc3pfF9W/vLUL/DDupP02eMe"
				+ "/GYhjTN0LxFW75QPUuE9WV5V7xTpZWRgTARS4r5zj0nbXH6WWpkZdljMsCtBPT6oQKBgQDlzEXHwC3OVzlvjWu70H9pGeBQpVvO5pHynylu74xoU1j7iIyEjjebhCvssUK+SciDhC32r"
				+ "ccCNMwmuo7ECbXRL8Co+Fzosta7PeXi2J6cBMRRgxEYMXhFMUEiLWfi0U/IAyOT3nf0rPyffQnV4l/vf/38oAlMe1L6itjCUhYdWwKBgQDeqp87Yyz7vk/oS0CWYPg1DWNsL3+rO5AgE"
				+ "MCUnDs4JvgwLn6pxYOCHnWmnKfg26eOZ8sWAVgWKHHSsOpQvMF9peu5Sax99SEvcprSltoWIt0woOYNYt35Ws7pHn2iJ0eDvdv0nEsecDLKwUPb6HI0e9u9ZIpRKz6EzDHUcmzncQKBg"
				+ "QDMZrSzBrg3z9Ig7ZQkjUKnhALI+Sz+jozHWNeL+7vSmECQDFehGLdRt3EyqkGJ7StUAGC6CLz5s2jcEhknOtPk7CrcoX5S5bCnAFnZO4FpmbveHU05TdeDBFrBQc4kLLZgXJpqzvm4Q"
				+ "565Zok3HJLk/941tUxQIWofvR0tS8Kr/QKBgFcpRweCPPuWdcUin/e9oatywDZ7KWin+jTzGc+SkIPcjU6fBKoETQREVB2SOQ0GLsLJ0m+cuxSBZHzrD+3m1X8P0gRH2KO9ru3Z2M0Si"
				+ "/AaWadNdIBM8UNIzrHmY5tz8MSbL1zDSYxysw83NA8DIKF20n+CubooSWLGY8Mbv5GhAoGBAKfb2FpADbUKXbc+J0gXXkp7zMKDu41E5kQCmAX8iDN0cLTGddNfdhQaxT3GYaI7CRKG1"
				+ "jojXUCTFdLlxKJlGjIi+dBa0uQT+CtEfotHO4xOkjZPqqf9MG13NTiteD5m6/FNaVi+R/LhPu14Llx1iialThpvEB19PwY/ybwaEtGS";

		email.signWithDomainKey(parseBase64Binary(privateDERkeyBase64), "somemail.com", "select");
		MimeMessage mimeMessage = Mailer.produceMimeMessage(email, Session.getDefaultInstance(new Properties()));
		Mailer.signMessageWithDKIM(mimeMessage, email);
	}
}