package org.simplejavamail.mailer;


import java.time.Duration;
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
import static org.simplejavamail.api.mailer.MailerGenericBuilder.DEFAULT_CONNECTIONPOOL_CLAIMTIMEOUT;
import static org.simplejavamail.api.mailer.MailerGenericBuilder.DEFAULT_CONNECTIONPOOL_EXPIREAFTER;
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
				.withConnectionPoolClaimTimeout(Duration.ofMillis(1_000))
				.withConnectionPoolExpireAfter(Duration.ofMillis(60_000))
				.resetConnectionPoolClaimTimeout();

		assertThat(builder.getConnectionPoolClaimTimeout()).isEqualTo(DEFAULT_CONNECTIONPOOL_CLAIMTIMEOUT);
		assertThat(builder.getConnectionPoolExpireAfter()).isEqualTo(Duration.ofMillis(60_000));
	}

	@Test
	public void resetConnectionPoolExpiryRestoresOnlyExpiry() {
		final MailerRegularBuilder<?> builder = SimpleJavaMail.withConfig(ConfigLoaderTestHelper.emptyConfig()).mailerBuilder().withSMTPServer("moo", 0)
				.withConnectionPoolClaimTimeout(Duration.ofMillis(1_000))
				.withConnectionPoolExpireAfter(Duration.ofMillis(60_000))
				.resetConnectionPoolExpireAfter();

		assertThat(builder.getConnectionPoolClaimTimeout()).isEqualTo(Duration.ofMillis(1_000));
		assertThat(builder.getConnectionPoolExpireAfter()).isEqualTo(DEFAULT_CONNECTIONPOOL_EXPIREAFTER);
	}

	@Test
	public void creationAgeExpiryCanBeConfiguredAndClearedIndependently() throws Exception {
		final MailerRegularBuilder<?> builder = SimpleJavaMail.withConfig(ConfigLoaderTestHelper.emptyConfig()).mailerBuilder().withSMTPServer("moo", 0);
		assertThat(builder.getConnectionPoolExpireAfterCreation()).isNull();
		builder.withConnectionPoolExpireAfter(Duration.ofMillis(60_000))
				.withConnectionPoolExpireAfterCreation(Duration.ofMillis(900_000));

		assertThat(builder.getConnectionPoolExpireAfterCreation()).isEqualTo(Duration.ofMillis(900_000));
		try (final Mailer mailer = builder.buildMailer()) {
			assertThat(mailer.getOperationalConfig().getConnectionPoolExpireAfterCreation()).isEqualTo(Duration.ofMillis(900_000));
		}
		assertThat(builder.clearConnectionPoolExpireAfterCreation()
				.getConnectionPoolExpireAfterCreation()).isNull();
		assertThat(builder.getConnectionPoolExpireAfter()).isEqualTo(Duration.ofMillis(60_000));
		assertThat(builder.withConnectionPoolExpireAfterCreation(Duration.ofMillis(0)).getConnectionPoolExpireAfterCreation()).isEqualTo(Duration.ZERO);
		assertThatThrownBy(builder::buildMailer)
				.isInstanceOf(IllegalArgumentException.class);
	}

	@Test
	public void creationAgeExpiryOverridesRemainLocalToTheBuilder() {
		final SimpleJavaMail mail = SimpleJavaMail.withConfig(ConfigLoaderTestHelper.config(
				singletonMap(ConfigLoader.Property.DEFAULT_CONNECTIONPOOL_EXPIREAFTERCREATION_MILLIS, 900_000)));
		final MailerRegularBuilder<?> builder = mail.mailerBuilder();

		assertThat(builder.getConnectionPoolExpireAfterCreation()).isEqualTo(Duration.ofMillis(900_000));
		assertThat(builder.withConnectionPoolExpireAfterCreation(Duration.ofMillis(600_000)).getConnectionPoolExpireAfterCreation()).isEqualTo(Duration.ofMillis(600_000));
		assertThat(builder.clearConnectionPoolExpireAfterCreation().getConnectionPoolExpireAfterCreation()).isNull();
		assertThat(mail.mailerBuilder().getConnectionPoolExpireAfterCreation()).isEqualTo(Duration.ofMillis(900_000));
	}
}
