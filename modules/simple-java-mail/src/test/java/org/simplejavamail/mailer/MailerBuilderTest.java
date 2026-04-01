package org.simplejavamail.mailer;

import org.junit.jupiter.api.Test;
import testutil.ConfigLoaderTestHelper;

public class MailerBuilderTest {
	@Test
	public void testClearedEmailAddressCriteria() {
		ConfigLoaderTestHelper.clearConfigProperties();
		MailerBuilder
				.withSMTPServer("moo", 0)
				.clearEmailValidator()
				.buildMailer();
		// good, no more errors due to #335
	}
}