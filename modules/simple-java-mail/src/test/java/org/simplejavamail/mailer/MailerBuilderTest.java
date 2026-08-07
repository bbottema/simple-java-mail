package org.simplejavamail.mailer;

import org.junit.jupiter.api.Test;
import org.simplejavamail.mailer.internal.MailerRegularBuilderImpl;
import testutil.ConfigLoaderTestHelper;

import static org.assertj.core.api.Assertions.assertThat;
import static org.simplejavamail.api.mailer.MailerGenericBuilder.DEFAULT_CONNECTIONPOOL_CLAIMTIMEOUT_MILLIS;
import static org.simplejavamail.api.mailer.MailerGenericBuilder.DEFAULT_CONNECTIONPOOL_EXPIREAFTER_MILLIS;
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

	@Test
	public void resetConnectionPoolClaimTimeoutRestoresOnlyClaimTimeout() {
		ConfigLoaderTestHelper.clearConfigProperties();
		final MailerRegularBuilderImpl builder = MailerBuilder.withSMTPServer("moo", 0)
				.withConnectionPoolClaimTimeoutMillis(1_000)
				.withConnectionPoolExpireAfterMillis(60_000)
				.resetConnectionPoolClaimTimeoutMillis();

		assertThat(builder.getConnectionPoolClaimTimeoutMillis()).isEqualTo(DEFAULT_CONNECTIONPOOL_CLAIMTIMEOUT_MILLIS);
		assertThat(builder.getConnectionPoolExpireAfterMillis()).isEqualTo(60_000);
	}

	@Test
	public void resetConnectionPoolExpiryRestoresOnlyExpiry() {
		ConfigLoaderTestHelper.clearConfigProperties();
		final MailerRegularBuilderImpl builder = MailerBuilder.withSMTPServer("moo", 0)
				.withConnectionPoolClaimTimeoutMillis(1_000)
				.withConnectionPoolExpireAfterMillis(60_000)
				.resetConnectionPoolExpireAfterMillis();

		assertThat(builder.getConnectionPoolClaimTimeoutMillis()).isEqualTo(1_000);
		assertThat(builder.getConnectionPoolExpireAfterMillis()).isEqualTo(DEFAULT_CONNECTIONPOOL_EXPIREAFTER_MILLIS);
	}
}
