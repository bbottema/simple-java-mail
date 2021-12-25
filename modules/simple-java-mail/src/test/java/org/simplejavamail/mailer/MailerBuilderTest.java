package org.simplejavamail.mailer;

import org.junit.Test;
import testutil.ConfigLoaderTestHelper;

public class MailerBuilderTest {
	@Test
	public void testClearedEmailAddressCriteria() {
		ConfigLoaderTestHelper.clearConfigProperties();
		MailerBuilder
				.withSMTPServer("moo", 0)
				.clearEmailAddressCriteria()
				.buildMailer();
		// good, no more errors due to #335
	}
}