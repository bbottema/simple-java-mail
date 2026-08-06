package org.simplejavamail.mailer;

import com.sanctionco.jmail.EmailValidator;
import org.junit.jupiter.api.Test;
import org.simplejavamail.mailer.internal.MailerRegularBuilderImpl;
import testutil.ConfigLoaderTestHelper;

import static org.assertj.core.api.Assertions.assertThat;

public class MailerBuilderTest {
	@Test
	public void clearEmailValidatorRemovesAddressPolicy() {
		ConfigLoaderTestHelper.clearConfigProperties();
		final MailerRegularBuilderImpl builder = MailerBuilder.withSMTPServer("moo", 0);

		builder.clearEmailValidator();

		assertThat(builder.getEmailValidator()).isNull();
		builder.buildMailer(); // clearing the address policy is a valid configuration; see #335
	}

	@Test
	public void resetEmailValidatorRestoresStrictDefault() {
		ConfigLoaderTestHelper.clearConfigProperties();
		final MailerRegularBuilderImpl builder = MailerBuilder.withSMTPServer("moo", 0)
				.clearEmailValidator()
				.resetEmailValidator();

		final EmailValidator restoredValidator = builder.getEmailValidator();
		assertThat(restoredValidator).isNotNull();
		assertThat(restoredValidator.isValid("alice@example.com")).isTrue();
		assertThat(restoredValidator.isValid("not-an-address")).isFalse();
	}
}
