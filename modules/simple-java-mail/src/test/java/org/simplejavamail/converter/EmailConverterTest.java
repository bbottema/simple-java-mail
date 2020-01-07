package org.simplejavamail.converter;

import org.jetbrains.annotations.NotNull;
import org.junit.Test;
import org.simplejavamail.api.email.Email;
import org.simplejavamail.api.email.EmailAssert;
import org.simplejavamail.api.email.Recipient;

import java.io.File;

import static demo.ResourceFolderHelper.determineResourceFolder;
import static javax.mail.Message.RecipientType.CC;
import static javax.mail.Message.RecipientType.TO;
import static org.assertj.core.api.Assertions.assertThat;
import static org.simplejavamail.internal.util.MiscUtil.normalizeNewlines;

public class EmailConverterTest {

	private static final String RESOURCE_FOLDER = determineResourceFolder("simple-java-mail") + "/test/resources/test-messages";

	@Test
	public void testOutlookBasicConversions() {
		final Recipient elias = new Recipient("Elias Laugher", "elias.laugher@gmail.com", null);
		final Recipient sven = new Recipient("Sven Sielenkemper", "sielenkemper@otris.de", TO);
		final Recipient niklas = new Recipient("niklas.lindson@gmail.com", "niklas.lindson@gmail.com", CC);

		@NotNull Email msg = EmailConverter.outlookMsgToEmail(new File(RESOURCE_FOLDER + "/simple email with TO and CC.msg"));
		EmailAssert.assertThat(msg).hasFromRecipient(elias);
		EmailAssert.assertThat(msg).hasSubject("Test E-Mail");
		EmailAssert.assertThat(msg).hasOnlyRecipients(sven, niklas);
		EmailAssert.assertThat(msg).hasNoAttachments();
		assertThat(msg.getPlainText()).isNotEmpty();
		assertThat(normalizeNewlines(msg.getHTMLText())).isEqualTo("<div dir=\"auto\">Just a test to get an email with one cc recipient.</div>\n");
		assertThat(normalizeNewlines(msg.getPlainText())).isEqualTo("Just a test to get an email with one cc recipient.\n");
	}
}