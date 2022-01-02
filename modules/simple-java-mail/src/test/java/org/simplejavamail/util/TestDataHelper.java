package org.simplejavamail.util;

import jakarta.mail.util.ByteArrayDataSource;
import org.jetbrains.annotations.NotNull;
import org.simplejavamail.api.email.AttachmentResource;
import org.simplejavamail.api.email.Email;
import org.simplejavamail.api.mailer.config.Pkcs12Config;

import java.io.IOException;
import java.net.HttpURLConnection;
import java.net.URL;

import static demo.ResourceFolderHelper.determineResourceFolder;
import static java.util.Objects.requireNonNull;

public class TestDataHelper {

	private static final String RESOURCES_PKCS = determineResourceFolder("simple-java-mail") + "/test/resources/pkcs12";

	/**
	 * Since the dresscode attachment name was overridden in the input Email, the original attachment's name is lost forever and won't come back when
	 * converted to MimeMessage. This would result in an unequal email if the MimeMessage was converted back again, so we need to either clear it in
	 * the input email after converting to MimeMessage, or re-add it to the Email result after converting from the MimeMessage.
	 * <p>
	 * We'll do the last to stay closes to the original input:
	 */
	public static void fixDresscodeAttachment(final Email emailResultFromConvertedMimeMessage) {
		for (AttachmentResource attachment : emailResultFromConvertedMimeMessage.getAttachments()) {
			if (requireNonNull(attachment.getName()).equals("dresscode.txt")) {
				((ByteArrayDataSource) attachment.getDataSource()).setName("dresscode-ignored-because-of-override.txt");
				break;
			}
		}
	}

	@NotNull
	public static Pkcs12Config loadPkcs12KeyStore() {
		return Pkcs12Config.builder()
				.pkcs12Store(RESOURCES_PKCS + "/smime_keystore.pkcs12")
				.storePassword("letmein")
				.keyAlias("smime_test_user_alias")
				.keyPassword("letmein")
				.build();
	}

	public static int getUrl(String urlStr) {
		try {
			return ((HttpURLConnection) new URL(urlStr).openConnection()).getResponseCode();
		} catch (IOException e) {
			return HttpURLConnection.HTTP_NOT_FOUND;
		}
	}
}
