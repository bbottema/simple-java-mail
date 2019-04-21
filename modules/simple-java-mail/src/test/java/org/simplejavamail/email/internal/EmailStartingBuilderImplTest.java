package org.simplejavamail.email.internal;

import org.junit.Test;
import org.simplejavamail.api.email.Email;
import org.simplejavamail.api.email.EmailAssert;
import org.simplejavamail.api.email.EmailPopulatingBuilder;
import org.simplejavamail.converter.EmailConverter;
import org.simplejavamail.email.EmailBuilder;

import java.io.File;

import static demo.ResourceFolderHelper.determineResourceFolder;
import static org.assertj.core.api.Assertions.assertThat;

public class EmailStartingBuilderImplTest {

	private static final String RESOURCE_FOLDER = determineResourceFolder("simple-java-mail") + "/test/resources/test-messages";

	@Test
	public void testCopyingSmimeSignedOutlookMessage() {
		final Email emailParsedFromMsg = EmailConverter.outlookMsgToEmail(new File(RESOURCE_FOLDER + "/SMIME (signed and clear text).msg"));
		final EmailPopulatingBuilder copyingEmailBuilder = EmailBuilder.ignoringDefaults().copying(emailParsedFromMsg);
		assertThat(copyingEmailBuilder.getHeaders()).isEmpty(); // when copying S/MIME generated message id should be ignored
		copyingEmailBuilder.withHeaders(emailParsedFromMsg.getHeaders()); // but for the equals check, manually add them
		EmailAssert.assertThat(copyingEmailBuilder.buildEmail()).isEqualTo(emailParsedFromMsg);
	}
}