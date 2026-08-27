package org.simplejavamail.mailer;

import jakarta.mail.internet.MimeMessage;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.MockedStatic;
import org.mockito.Mockito;
import org.simplejavamail.api.SimpleJavaMail;
import org.simplejavamail.api.email.Email;
import org.simplejavamail.api.email.Recipient;
import org.simplejavamail.api.email.config.DkimConfig;
import org.simplejavamail.api.mailer.EmailTooBigException;
import org.simplejavamail.api.mailer.MailRehearsal;
import org.simplejavamail.api.mailer.Mailer;
import org.simplejavamail.api.mailer.MailerRegularBuilder;
import org.simplejavamail.converter.EmailConverter;
import org.simplejavamail.internal.moduleloader.ModuleLoader;
import org.simplejavamail.internal.modules.DKIMModule;
import org.simplejavamail.internal.util.FinalizedMimeMessage;
import testutil.ConfigLoaderTestHelper;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.atomic.AtomicReference;

import static jakarta.mail.Message.RecipientType.TO;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.simplejavamail.internal.util.FinalizedMimeMessage.ProtectionState.FINAL_WIRE_SIGNED;

class MailerRehearsalTest {

	private SimpleJavaMail simpleJavaMail;

	@BeforeEach
	void setUp() {
		simpleJavaMail = SimpleJavaMail.withConfig(ConfigLoaderTestHelper.emptyConfig());
	}

	@Test
	void rehearsalReturnsTheEffectiveMessageAndTransportEnvelopeWithoutChangingTheDraft() throws Exception {
		final Email draft = simpleJavaMail.emailBuilder().startingBlank()
				.withSubject("Governed rehearsal")
				.withPlainText("Body")
				.withOverrideReceivers(recipient("actual@example.com"))
				.buildEmail();
		final Email defaults = simpleJavaMail.emailBuilder().startingBlank()
				.from("Default sender", "default@example.com")
				.withRecipients(recipient("visible@example.com"))
				.withBounceTo("bounce@example.com")
				.buildEmail();

		final MailRehearsal rehearsal;
		try (Mailer mailer = mailerBuilder()
				.withEmailDefaults(defaults)
				.buildMailer()) {
			rehearsal = mailer.rehearse(draft);
		}

		assertThat(rehearsal.isFullRehearsal()).isTrue();
		assertThat(rehearsal.getEffectiveEmail()).isNotSameAs(draft);
		assertThat(rehearsal.getEffectiveEmail().getFromRecipient().getAddress()).isEqualTo("default@example.com");
		assertThat(rehearsal.getEffectiveEmail().getRecipients())
				.extracting(Recipient::getAddress)
				.containsExactly("visible@example.com");
		assertThat(rehearsal.getEnvelopeSender()).isEqualTo("bounce@example.com");
		assertThat(rehearsal.getEnvelopeRecipients()).containsExactly("actual@example.com");

		final byte[] emlBytes = rehearsal.getEmlBytes();
		final MimeMessage mimeMessage = EmailConverter.emlToMimeMessage(new ByteArrayInputStream(emlBytes));
		assertThat(rehearsal.getEncodedSize()).isEqualTo(emlBytes.length);
		assertThat(rehearsal.getEmailId()).isEqualTo(mimeMessage.getMessageID());
		assertThat(rehearsal.getEffectiveEmail().getId()).isEqualTo(rehearsal.getEmailId());
		assertThat(mimeMessage.getSubject()).isEqualTo("Governed rehearsal");
		assertThat(mimeMessage.getAllRecipients()).extracting(Object::toString).containsExactly("visible@example.com");

		assertThat(draft.getFromRecipient()).isNull();
		assertThat(draft.getRecipients()).isEmpty();
		assertThat(draft.getBounceToRecipient()).isNull();
		assertThat(draft.getId()).isNull();
	}

	@Test
	void rehearsalResultDefensivelyOwnsItsBytesAndRecipientList() throws Exception {
		final MailRehearsal rehearsal;
		try (Mailer mailer = mailerBuilder().buildMailer()) {
			rehearsal = mailer.rehearse(validEmail(), false);
		}

		final byte[] firstRead = rehearsal.getEmlBytes();
		final byte[] expectedBytes = firstRead.clone();
		firstRead[0] = (byte) (firstRead[0] + 1);

		assertThat(rehearsal.getEmlBytes()).containsExactly(expectedBytes);
		assertThatThrownBy(() -> rehearsal.getEnvelopeRecipients().add("other@example.com"))
				.isInstanceOf(UnsupportedOperationException.class);
	}

	@Test
	void fastRehearsalReturnsBaseMimeFactsWithoutEnforcingTheMaximumSize() throws Exception {
		final Email draft = validEmail();

		try (Mailer mailer = mailerBuilder()
				.withMaximumEmailSize(4)
				.buildMailer()) {
			final MailRehearsal rehearsal = mailer.rehearse(draft, false);

			assertThat(rehearsal.isFullRehearsal()).isFalse();
			assertThat(rehearsal.getEncodedSize()).isGreaterThan(4);
			assertThat(rehearsal.getEmailId()).isNotBlank();
			assertThat(rehearsal.getEnvelopeSender()).isNull();
			assertThat(rehearsal.getEnvelopeRecipients()).containsExactly("recipient@example.com");
			assertThatThrownBy(() -> mailer.rehearse(draft))
					.isInstanceOf(EmailTooBigException.class)
					.hasMessageContaining("exceeds maximum allowed size of 4 bytes");
		}

		assertThat(draft.getId()).isNull();
	}

	@Test
	void fullRehearsalPreservesAuthoritativeBytesReturnedByTheSecurityPipeline() throws Exception {
		final Email email = simpleJavaMail.emailBuilder().copying(validEmail())
				.signWithDomainKey(DkimConfig.builder()
						.dkimPrivateKeyData("unused-test-key")
						.dkimSigningDomain("example.com")
						.dkimSelector("test")
						.build())
				.buildEmail();
		final DKIMModule dkimModule = mock(DKIMModule.class);
		final AtomicReference<byte[]> authoritativeBytes = new AtomicReference<>();
		when(dkimModule.signMessageWithDKIM(any(Email.class), any(MimeMessage.class), any(DkimConfig.class), any(Recipient.class)))
				.thenAnswer(invocation -> {
					final MimeMessage message = invocation.getArgument(1);
					message.setHeader("X-Rehearsal-Security", "processed");
					final FinalizedMimeMessage finalized = FinalizedMimeMessage.finalizeMessage(message, FINAL_WIRE_SIGNED);
					authoritativeBytes.set(finalized.getSerializedBytes());
					return finalized;
				});

		try (Mailer mailer = mailerBuilder().buildMailer();
			 MockedStatic<ModuleLoader> moduleLoader = Mockito.mockStatic(ModuleLoader.class, Mockito.CALLS_REAL_METHODS)) {
			moduleLoader.when(ModuleLoader::loadDKIMModule).thenReturn(dkimModule);

			final MailRehearsal rehearsal = mailer.rehearse(email);

			assertThat(rehearsal.getEmlBytes()).containsExactly(authoritativeBytes.get());
			assertThat(EmailConverter.emlToMimeMessage(new ByteArrayInputStream(rehearsal.getEmlBytes()))
					.getHeader("X-Rehearsal-Security", null)).isEqualTo("processed");
		}
	}

	@Test
	void exactEmlRehearsalReturnsTheSuppliedBytesAndMeasuresTheirRawLength() throws Exception {
		final byte[] exactEml = ("Subject: Exact rehearsal\r\n"
				+ "X-Folded: first\r\n"
				+ " second\r\n"
				+ "\r\n"
				+ "body\r\n").getBytes(StandardCharsets.US_ASCII);
		final Email email = simpleJavaMail.emailBuilder().startingFromExactEml(exactEml)
				.withEnvelopeRecipients("recipient@example.org")
				.buildEmail();

		try (Mailer exactLimit = mailerBuilder()
				.withMaximumEmailSize(exactEml.length)
				.buildMailer();
			 Mailer tooSmall = mailerBuilder()
					.withMaximumEmailSize(exactEml.length - 1)
					.buildMailer()) {
			final MailRehearsal rehearsal = exactLimit.rehearse(email);

			assertThat(rehearsal.getEffectiveEmail()).isSameAs(email);
			assertThat(rehearsal.getEmlBytes()).containsExactly(exactEml);
			assertThat(rehearsal.getEncodedSize()).isEqualTo(exactEml.length);
			assertThat(rehearsal.getEmailId()).isNull();
			assertThat(rehearsal.getEnvelopeRecipients()).containsExactly("recipient@example.org");
			assertThat(tooSmall.rehearse(email, false).getEmlBytes()).containsExactly(exactEml);
			assertThatThrownBy(() -> tooSmall.rehearse(email))
					.isInstanceOf(EmailTooBigException.class)
					.hasMessageContaining(exactEml.length + " bytes exceeds maximum allowed size of " + (exactEml.length - 1) + " bytes");
		}
	}

	private MailerRegularBuilder<?> mailerBuilder() {
		return simpleJavaMail.mailerBuilder().withSMTPServer("unreachable.example.invalid", 25);
	}

	private Email validEmail() {
		return simpleJavaMail.emailBuilder().startingBlank()
				.from("sender@example.com")
				.withRecipients(recipient("recipient@example.com"))
				.withSubject("Rehearsal")
				.withPlainText("Body")
				.buildEmail();
	}

	private static Recipient recipient(final String address) {
		return new Recipient(null, address, TO, null);
	}
}
