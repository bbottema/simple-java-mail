package org.simplejavamail.mailer;

import org.simplejavamail.api.mailer.config.ConnectionPoolConfig;
import org.simplejavamail.api.mailer.config.ValidationConfig;
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

		builder.withValidationConfig(builder.getValidationConfig().toBuilder().clearEmailValidator().build());

		assertThat(builder.getValidationConfig().getEmailValidator()).isNull();
		builder.buildMailer(); // clearing the address policy is a valid configuration; see #335
	}

	@Test
	public void resetEmailValidatorRestoresStrictDefault() {
		final MailerRegularBuilder<?> builder = SimpleJavaMail.withConfig(ConfigLoaderTestHelper.emptyConfig()).mailerBuilder().withSMTPServer("moo", 0)
				.withValidationConfig(ValidationConfig.builder().clearEmailValidator().resetEmailValidator().build());

		final EmailValidator restoredValidator = builder.getValidationConfig().getEmailValidator();
		assertThat(restoredValidator).isNotNull();
		assertThat(restoredValidator.isValid("alice@example.com")).isTrue();
		assertThat(restoredValidator.isValid("not-an-address")).isFalse();
	}

	@Test
	public void resetConnectionPoolMaxSizeRestoresOnlyMaxSize() {
		final MailerRegularBuilder<?> builder = SimpleJavaMail.withConfig(ConfigLoaderTestHelper.emptyConfig()).mailerBuilder().withSMTPServer("moo", 0)
				.withConnectionPoolConfig(ConnectionPoolConfig.builder().withCoreSize(2).withMaxSize(12).resetMaxSize().build());

		assertThat(builder.getConnectionPoolConfig().getCoreSize()).isEqualTo(2);
		assertThat(builder.getConnectionPoolConfig().getMaxSize()).isEqualTo(DEFAULT_CONNECTIONPOOL_MAX_SIZE);
	}

	@Test
	public void resetConnectionPoolClaimTimeoutRestoresOnlyClaimTimeout() {
		final MailerRegularBuilder<?> builder = SimpleJavaMail.withConfig(ConfigLoaderTestHelper.emptyConfig()).mailerBuilder().withSMTPServer("moo", 0)
				.withConnectionPoolConfig(ConnectionPoolConfig.builder().withClaimTimeout(Duration.ofMillis(1_000)).withExpireAfter(Duration.ofMillis(60_000)).resetClaimTimeout().build());

		assertThat(builder.getConnectionPoolConfig().getClaimTimeout()).isEqualTo(DEFAULT_CONNECTIONPOOL_CLAIMTIMEOUT);
		assertThat(builder.getConnectionPoolConfig().getExpireAfter()).isEqualTo(Duration.ofMillis(60_000));
	}

	@Test
	public void resetConnectionPoolExpiryRestoresOnlyExpiry() {
		final MailerRegularBuilder<?> builder = SimpleJavaMail.withConfig(ConfigLoaderTestHelper.emptyConfig()).mailerBuilder().withSMTPServer("moo", 0)
				.withConnectionPoolConfig(ConnectionPoolConfig.builder().withClaimTimeout(Duration.ofMillis(1_000)).withExpireAfter(Duration.ofMillis(60_000)).resetExpireAfter().build());

		assertThat(builder.getConnectionPoolConfig().getClaimTimeout()).isEqualTo(Duration.ofMillis(1_000));
		assertThat(builder.getConnectionPoolConfig().getExpireAfter()).isEqualTo(DEFAULT_CONNECTIONPOOL_EXPIREAFTER);
	}

	@Test
	public void creationAgeExpiryCanBeConfiguredAndClearedIndependently() throws Exception {
		final MailerRegularBuilder<?> builder = SimpleJavaMail.withConfig(ConfigLoaderTestHelper.emptyConfig()).mailerBuilder().withSMTPServer("moo", 0);
		assertThat(builder.getConnectionPoolConfig().getExpireAfterCreation()).isNull();
		builder.withConnectionPoolConfig(builder.getConnectionPoolConfig().toBuilder().withExpireAfter(Duration.ofMillis(60_000)).withExpireAfterCreation(Duration.ofMillis(900_000)).build());

		assertThat(builder.getConnectionPoolConfig().getExpireAfterCreation()).isEqualTo(Duration.ofMillis(900_000));
		try (final Mailer mailer = builder.buildMailer()) {
			assertThat(mailer.getOperationalConfig().getConnectionPoolExpireAfterCreation()).isEqualTo(Duration.ofMillis(900_000));
		}
		assertThat(builder.withConnectionPoolConfig(builder.getConnectionPoolConfig().toBuilder().clearExpireAfterCreation().build())
				.getConnectionPoolConfig().getExpireAfterCreation()).isNull();
		assertThat(builder.getConnectionPoolConfig().getExpireAfter()).isEqualTo(Duration.ofMillis(60_000));
		assertThat(builder.withConnectionPoolConfig(builder.getConnectionPoolConfig().toBuilder().withExpireAfterCreation(Duration.ofMillis(0)).build()).getConnectionPoolConfig().getExpireAfterCreation()).isEqualTo(Duration.ZERO);
		assertThatThrownBy(builder::buildMailer)
				.isInstanceOf(IllegalArgumentException.class);
	}

	@Test
	public void creationAgeExpiryOverridesRemainLocalToTheBuilder() throws Exception {
		final SimpleJavaMail mail = SimpleJavaMail.withConfig(ConfigLoaderTestHelper.config(
				singletonMap(ConfigLoader.Property.DEFAULT_CONNECTIONPOOL_EXPIREAFTERCREATION_MILLIS, 900_000)));
		final MailerRegularBuilder<?> builder = mail.mailerBuilder();

		assertThat(builder.getConnectionPoolConfig().getExpireAfterCreation()).isNull();
		try (Mailer inherited = builder.withSMTPServerHost("localhost").buildMailer()) {
			assertThat(inherited.getOperationalConfig().getConnectionPoolExpireAfterCreation()).isEqualTo(Duration.ofMillis(900_000));
		}
		assertThat(builder.withConnectionPoolConfig(builder.getConnectionPoolConfig().toBuilder().withExpireAfterCreation(Duration.ofMillis(600_000)).build()).getConnectionPoolConfig().getExpireAfterCreation()).isEqualTo(Duration.ofMillis(600_000));
		assertThat(builder.withConnectionPoolConfig(builder.getConnectionPoolConfig().toBuilder().clearExpireAfterCreation().build()).getConnectionPoolConfig().getExpireAfterCreation()).isNull();
		assertThat(mail.mailerBuilder().getConnectionPoolConfig().getExpireAfterCreation()).isNull();
		try (Mailer unchanged = mail.mailerBuilder().withSMTPServerHost("localhost").buildMailer()) {
			assertThat(unchanged.getOperationalConfig().getConnectionPoolExpireAfterCreation()).isEqualTo(Duration.ofMillis(900_000));
		}
	}
}
