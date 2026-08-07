package org.simplejavamail.mailer;

import org.junit.jupiter.api.Test;
import org.simplejavamail.mailer.internal.MailerRegularBuilderImpl;
import testutil.ConfigLoaderTestHelper;

import static org.assertj.core.api.Assertions.assertThat;
import static org.simplejavamail.api.mailer.MailerGenericBuilder.DEFAULT_CONNECTIONPOOL_MAX_SIZE;

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

	@Test
	public void resetConnectionPoolMaxSizeRestoresOnlyMaxSize() {
		ConfigLoaderTestHelper.clearConfigProperties();
		final MailerRegularBuilderImpl builder = MailerBuilder.withSMTPServer("moo", 0)
				.withConnectionPoolCoreSize(2)
				.withConnectionPoolMaxSize(12)
				.resetConnectionPoolMaxSize();

		assertThat(builder.getConnectionPoolCoreSize()).isEqualTo(2);
		assertThat(builder.getConnectionPoolMaxSize()).isEqualTo(DEFAULT_CONNECTIONPOOL_MAX_SIZE);
	}
}
