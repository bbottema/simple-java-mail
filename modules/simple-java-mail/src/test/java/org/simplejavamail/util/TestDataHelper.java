package org.simplejavamail.util;

import org.simplejavamail.api.email.AttachmentResource;
import org.simplejavamail.api.email.Email;

import javax.mail.util.ByteArrayDataSource;

import static java.util.Objects.requireNonNull;

public class TestDataHelper {
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
}
