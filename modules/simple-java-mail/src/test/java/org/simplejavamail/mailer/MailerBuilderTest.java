package org.simplejavamail.mailer;


import org.simplejavamail.api.SimpleJavaMail;

import com.sanctionco.jmail.EmailValidator;
import org.junit.jupiter.api.Test;
import org.simplejavamail.api.mailer.Mailer;
import org.simplejavamail.api.mailer.MailerRegularBuilder;
import org.simplejavamail.config.ConfigLoader;
import testutil.ConfigLoaderTestHelper;

import static java.util.Collections.singletonMap;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.simplejavamail.api.mailer.MailerGenericBuilder.DEFAULT_CONNECTIONPOOL_CLAIMTIMEOUT_MILLIS;
import static org.simplejavamail.api.mailer.MailerGenericBuilder.DEFAULT_CONNECTIONPOOL_EXPIREAFTER_MILLIS;
import static org.simplejavamail.api.mailer.MailerGenericBuilder.DEFAULT_CONNECTIONPOOL_MAX_SIZE;

public class MailerBuilderTest {
	@Test
	public void clearEmailValidatorRemovesAddressPolicy() {
		final MailerRegularBuilder<?> builder = SimpleJavaMail.withConfig(ConfigLoaderTestHelper.emptyConfig()).mailerBuilder().withSMTPServer("moo", 0);

		builder.clearEmailValidator();

		assertThat(builder.getEmailValidator()).isNull();
		builder.buildMailer(); // clearing the address policy is a valid configuration; see #335
	}

	@Test
	public void resetEmailValidatorRestoresStrictDefault() {
		final MailerRegularBuilder<?> builder = SimpleJavaMail.withConfig(ConfigLoaderTestHelper.emptyConfig()).mailerBuilder().withSMTPServer("moo", 0)
				.clearEmailValidator()
				.resetEmailValidator();

		final EmailValidator restoredValidator = builder.getEmailValidator();
		assertThat(restoredValidator).isNotNull();
		assertThat(restoredValidator.isValid("alice@example.com")).isTrue();
		assertThat(restoredValidator.isValid("not-an-address")).isFalse();
	}

	@Test
	public void resetConnectionPoolMaxSizeRestoresOnlyMaxSize() {
		final MailerRegularBuilder<?> builder = SimpleJavaMail.withConfig(ConfigLoaderTestHelper.emptyConfig()).mailerBuilder().withSMTPServer("moo", 0)
				.withConnectionPoolCoreSize(2)
				.withConnectionPoolMaxSize(12)
				.resetConnectionPoolMaxSize();

		assertThat(builder.getConnectionPoolCoreSize()).isEqualTo(2);
		assertThat(builder.getConnectionPoolMaxSize()).isEqualTo(DEFAULT_CONNECTIONPOOL_MAX_SIZE);
	}

	@Test
	public void resetConnectionPoolClaimTimeoutRestoresOnlyClaimTimeout() {
		final MailerRegularBuilder<?> builder = SimpleJavaMail.withConfig(ConfigLoaderTestHelper.emptyConfig()).mailerBuilder().withSMTPServer("moo", 0)
				.withConnectionPoolClaimTimeoutMillis(1_000)
				.withConnectionPoolExpireAfterMillis(60_000)
				.resetConnectionPoolClaimTimeoutMillis();

		assertThat(builder.getConnectionPoolClaimTimeoutMillis()).isEqualTo(DEFAULT_CONNECTIONPOOL_CLAIMTIMEOUT_MILLIS);
		assertThat(builder.getConnectionPoolExpireAfterMillis()).isEqualTo(60_000);
	}

	@Test
	public void resetConnectionPoolExpiryRestoresOnlyExpiry() {
		final MailerRegularBuilder<?> builder = SimpleJavaMail.withConfig(ConfigLoaderTestHelper.emptyConfig()).mailerBuilder().withSMTPServer("moo", 0)
				.withConnectionPoolClaimTimeoutMillis(1_000)
				.withConnectionPoolExpireAfterMillis(60_000)
				.resetConnectionPoolExpireAfterMillis();

		assertThat(builder.getConnectionPoolClaimTimeoutMillis()).isEqualTo(1_000);
		assertThat(builder.getConnectionPoolExpireAfterMillis()).isEqualTo(DEFAULT_CONNECTIONPOOL_EXPIREAFTER_MILLIS);
	}

	@Test
	public void creationAgeExpiryCanBeConfiguredAndClearedIndependently() throws Exception {
		final MailerRegularBuilder<?> builder = SimpleJavaMail.withConfig(ConfigLoaderTestHelper.emptyConfig()).mailerBuilder().withSMTPServer("moo", 0);
		assertThat(builder.getConnectionPoolExpireAfterCreationMillis()).isNull();
		builder.withConnectionPoolExpireAfterMillis(60_000)
				.withConnectionPoolExpireAfterCreationMillis(900_000);

		assertThat(builder.getConnectionPoolExpireAfterCreationMillis()).isEqualTo(900_000);
		try (final Mailer mailer = builder.buildMailer()) {
			assertThat(mailer.getOperationalConfig().getConnectionPoolExpireAfterCreationMillis()).isEqualTo(900_000);
		}
		assertThat(builder.clearConnectionPoolExpireAfterCreationMillis()
				.getConnectionPoolExpireAfterCreationMillis()).isNull();
		assertThat(builder.getConnectionPoolExpireAfterMillis()).isEqualTo(60_000);
		assertThatThrownBy(() -> builder.withConnectionPoolExpireAfterCreationMillis(0))
				.isInstanceOf(IllegalArgumentException.class);
	}

	@Test
	public void creationAgeExpiryOverridesRemainLocalToTheBuilder() {
		final SimpleJavaMail mail = SimpleJavaMail.withConfig(ConfigLoaderTestHelper.config(
				singletonMap(ConfigLoader.Property.DEFAULT_CONNECTIONPOOL_EXPIREAFTERCREATION_MILLIS, 900_000)));
		final MailerRegularBuilder<?> builder = mail.mailerBuilder();

		assertThat(builder.getConnectionPoolExpireAfterCreationMillis()).isEqualTo(900_000);
		assertThat(builder.withConnectionPoolExpireAfterCreationMillis(600_000).getConnectionPoolExpireAfterCreationMillis()).isEqualTo(600_000);
		assertThat(builder.clearConnectionPoolExpireAfterCreationMillis().getConnectionPoolExpireAfterCreationMillis()).isNull();
		assertThat(mail.mailerBuilder().getConnectionPoolExpireAfterCreationMillis()).isEqualTo(900_000);
	}
}
