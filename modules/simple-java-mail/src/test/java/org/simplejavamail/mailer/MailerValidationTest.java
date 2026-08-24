package org.simplejavamail.mailer;

import com.sanctionco.jmail.JMail;
import jakarta.mail.internet.MimeMessage;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.MockedStatic;
import org.mockito.Mockito;
import org.simplejavamail.api.SimpleJavaMail;
import org.simplejavamail.api.email.Email;
import org.simplejavamail.api.email.Recipient;
import org.simplejavamail.api.email.config.DkimConfig;
import org.simplejavamail.api.mailer.CustomMailer;
import org.simplejavamail.api.mailer.EmailTooBigException;
import org.simplejavamail.api.mailer.Mailer;
import org.simplejavamail.api.mailer.MailerRegularBuilder;
import org.simplejavamail.internal.moduleloader.ModuleLoader;
import org.simplejavamail.internal.modules.DKIMModule;
import testutil.ConfigLoaderTestHelper;

import static jakarta.mail.Message.RecipientType.TO;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class MailerValidationTest {

	private SimpleJavaMail simpleJavaMail;

	@BeforeEach
	void setUp() {
		simpleJavaMail = SimpleJavaMail.withConfig(ConfigLoaderTestHelper.emptyConfig());
	}

	@Test
	void validationUsesMailerDefaultsWithoutChangingTheDraft() throws Exception {
		final Email draft = simpleJavaMail.emailBuilder().startingBlank()
				.withSubject("Governed validation")
				.withPlainText("Body")
				.buildEmail();
		final Email defaults = simpleJavaMail.emailBuilder().startingBlank()
				.from("default@example.com")
				.withRecipients(recipient("recipient@example.com"))
				.buildEmail();

		try (Mailer mailer = simpleJavaMail.mailerBuilder()
				.withSMTPServer("unreachable.example.invalid", 25)
				.withEmailDefaults(defaults)
				.buildMailer()) {
			assertThat(mailer.validate(draft)).isTrue();
		}

		assertThat(draft.getFromRecipient()).isNull();
		assertThat(draft.getRecipients()).isEmpty();
		assertThat(draft.getId()).isNull();
	}

	@Test
	void validationUsesMailerOverridesBeforeAddressValidation() throws Exception {
		final Email draft = validEmail("not-an-email-address");
		final Email overrides = simpleJavaMail.emailBuilder().startingBlank()
				.from("override@example.com")
				.buildEmail();

		try (Mailer mailerWithoutOverride = mailerBuilder()
				.withEmailValidator(JMail.strictValidator())
				.buildMailer();
			 Mailer mailerWithOverride = mailerBuilder()
					.withEmailValidator(JMail.strictValidator())
					.withEmailOverrides(overrides)
					.buildMailer()) {
			assertThatThrownBy(() -> mailerWithoutOverride.validate(draft, false))
					.isInstanceOf(MailInvalidAddressException.class);
			assertThat(mailerWithOverride.validate(draft, false)).isTrue();
		}

		assertThat(draft.getFromRecipient().getAddress()).isEqualTo("not-an-email-address");
	}

	@Test
	void securityAndSizeFlagControlsDkimProcessing() throws Exception {
		final Email email = simpleJavaMail.emailBuilder().copying(validEmail("sender@example.com"))
				.signWithDomainKey(DkimConfig.builder()
						.dkimPrivateKeyData("unused-test-key")
						.dkimSigningDomain("example.com")
						.dkimSelector("test")
						.build())
				.buildEmail();
		final DKIMModule dkimModule = mock(DKIMModule.class);
		when(dkimModule.signMessageWithDKIM(any(Email.class), any(MimeMessage.class), any(DkimConfig.class), any(Recipient.class)))
				.thenAnswer(invocation -> invocation.getArgument(1));

		try (Mailer mailer = mailerBuilder().buildMailer();
			 MockedStatic<ModuleLoader> moduleLoader = Mockito.mockStatic(ModuleLoader.class, Mockito.CALLS_REAL_METHODS)) {
			moduleLoader.when(ModuleLoader::loadDKIMModule).thenReturn(dkimModule);
			moduleLoader.clearInvocations();

			assertThat(mailer.validate(email, false)).isTrue();
			moduleLoader.verify(ModuleLoader::loadDKIMModule, never());
			verifyNoInteractions(dkimModule);

			assertThat(mailer.validate(email)).isTrue();
			verify(dkimModule).signMessageWithDKIM(any(Email.class), any(MimeMessage.class), any(DkimConfig.class), any(Recipient.class));
		}
	}

	@Test
	void defaultAndExplicitFullValidationEnforceTheEncodedSizeLimit() throws Exception {
		final Email email = validEmail("sender@example.com");

		try (Mailer mailer = mailerBuilder()
				.withMaximumEmailSize(4)
				.buildMailer()) {
			assertThat(mailer.validate(email, false)).isTrue();
			assertThatThrownBy(() -> mailer.validate(email))
					.isInstanceOf(EmailTooBigException.class)
					.hasMessageContaining("exceeds maximum allowed size of 4 bytes");
			assertThatThrownBy(() -> mailer.validate(email, true))
					.isInstanceOf(EmailTooBigException.class)
					.hasMessageContaining("exceeds maximum allowed size of 4 bytes");
		}

		assertThat(email.getId()).isNull();
	}

	@Test
	void validationDoesNotInvokeTheCustomMailer() throws Exception {
		final CustomMailer customMailer = mock(CustomMailer.class);

		try (Mailer mailer = mailerBuilder()
				.withCustomMailer(customMailer)
				.buildMailer()) {
			assertThat(mailer.validate(validEmail("sender@example.com"))).isTrue();
		}

		verifyNoInteractions(customMailer);
	}

	private MailerRegularBuilder<?> mailerBuilder() {
		return simpleJavaMail.mailerBuilder().withSMTPServer("unreachable.example.invalid", 25);
	}

	private Email validEmail(final String fromAddress) {
		return simpleJavaMail.emailBuilder().startingBlank()
				.from(fromAddress)
				.withRecipients(recipient("recipient@example.com"))
				.withSubject("Validation")
				.withPlainText("Body")
				.buildEmail();
	}

	private static Recipient recipient(final String address) {
		return new Recipient(null, address, TO, null);
	}
}
