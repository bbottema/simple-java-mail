package org.simplejavamail.mailer.internal;

import jakarta.mail.MessagingException;
import jakarta.mail.Multipart;
import jakarta.mail.Session;
import jakarta.mail.internet.MimeMessage;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.simplejavamail.api.SimpleJavaMail;
import org.simplejavamail.api.email.Email;
import org.simplejavamail.api.email.EmailPopulatingBuilder;
import org.simplejavamail.api.mailer.MailRehearsal;
import org.simplejavamail.api.mailer.Mailer;
import org.simplejavamail.config.ConfigLoader;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Properties;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.simplejavamail.api.email.EmailProperty.MAIL_TO_FORWARD;

class ForwardedTemplateGovernanceTest {

	private final SimpleJavaMail mail = SimpleJavaMail.withConfig(ConfigLoader.builder().load());

	@Test
	void defaultForwardAlsoAppliesThroughExplicitBuilderCompletion() throws Exception {
		final MimeMessage source = source("Default source");
		try (Mailer mailer = mailer(forward(source).buildEmail(), blank().buildEmail())) {
			final Email completed = blank().buildEmailCompletedWithDefaultsAndOverrides(mailer.getEmailGovernance());

			assertThat(completed.getEmailToForward()).isSameAs(source);
		}
	}

	@Test
	void suppliedForwardWinsOverTheDefault() throws Exception {
		final MimeMessage supplied = source("Supplied source");
		try (Mailer mailer = mailer(forward(source("Default source")).buildEmail(), blank().buildEmail())) {
			assertThat(govern(mailer, forward(supplied).buildEmail()).getEmailToForward()).isSameAs(supplied);
		}
	}

	@Test
	void overrideForwardWinsOverSuppliedAndDefaultSources() throws Exception {
		final MimeMessage overridden = source("Override source");
		try (Mailer mailer = mailer(forward(source("Default source")).buildEmail(), forward(overridden).buildEmail())) {
			final Email resolved = govern(mailer, forward(source("Supplied source")).buildEmail());

			assertThat(resolved.getEmailToForward()).isSameAs(overridden);
			assertThat(resolved.getSubject()).isEqualTo("Fwd: Override source");
		}
	}

	@ParameterizedTest
	@ValueSource(booleans = {false, true})
	void suppressingDefaultsRemovesTheirForwardedSource(final boolean globally) throws Exception {
		final EmailPopulatingBuilder provided = blank();
		if (globally) {
			provided.ignoringDefaults();
		} else {
			provided.dontApplyDefaultValueFor(MAIL_TO_FORWARD);
		}
		try (Mailer mailer = mailer(forward(source("Default source")).buildEmail(), blank().buildEmail())) {
			assertThat(govern(mailer, provided.buildEmail()).getEmailToForward()).isNull();
		}
	}

	@ParameterizedTest
	@ValueSource(booleans = {false, true})
	void suppressingOverridesFallsBackToTheDefaultForward(final boolean globally) throws Exception {
		final MimeMessage defaultSource = source("Default source");
		final EmailPopulatingBuilder provided = blank();
		if (globally) {
			provided.ignoringOverrides();
		} else {
			provided.dontApplyOverrideValueFor(MAIL_TO_FORWARD);
		}
		try (Mailer mailer = mailer(forward(defaultSource).buildEmail(), forward(source("Override source")).buildEmail())) {
			assertThat(govern(mailer, provided.buildEmail()).getEmailToForward()).isSameAs(defaultSource);
		}
	}

	@Test
	void suppressingTemplatesNeverRemovesTheSuppliedForward() throws Exception {
		final MimeMessage supplied = source("Supplied source");
		final Email provided = forward(supplied).ignoringDefaults().ignoringOverrides()
				.dontApplyDefaultValueFor(MAIL_TO_FORWARD).dontApplyOverrideValueFor(MAIL_TO_FORWARD).buildEmail();
		try (Mailer mailer = mailer(forward(source("Default source")).buildEmail(), forward(source("Override source")).buildEmail())) {
			assertThat(govern(mailer, provided).getEmailToForward()).isSameAs(supplied);
		}
	}

	@Test
	void ordinaryMessagesRemainOrdinaryWhenNoForwardIsConfigured() throws Exception {
		try (Mailer mailer = mailer(blank().withSubject("Default subject").buildEmail(), blank().buildEmail())) {
			final Email resolved = govern(mailer, blank().withPlainText("Original body").buildEmail());

			assertThat(resolved.getEmailToForward()).isNull();
			assertThat(resolved.getSubject()).isEqualTo("Default subject");
			assertThat(resolved.getPlainText()).isEqualTo("Original body");
		}
	}

	@Test
	void nullSubmittedEmailCanStillUseAForwardedTemplate() throws Exception {
		final MimeMessage source = source("Default source");
		try (Mailer mailer = mailer(forward(source).buildEmail(), blank().buildEmail())) {
			assertThat(govern(mailer, null).getEmailToForward()).isSameAs(source);
		}
	}

	@Test
	void copyingAndConcurrentGovernanceBorrowTheChosenSourceWithoutReadingContent() throws Exception {
		final ContentCountingMessage source = new ContentCountingMessage();
		source.setSubject("Borrowed source");
		source.setText("Borrowed body");
		final Email template = forward(source).buildEmail();
		final Email provided = blank().buildEmail();
		final ExecutorService workers = Executors.newFixedThreadPool(4);
		try (Mailer mailer = mailer(template, blank().buildEmail())) {
			final List<Callable<Email>> preparations = new ArrayList<>();
			for (int preparation = 0; preparation < 16; preparation++) {
				preparations.add(() -> mail.emailBuilder().copying(govern(mailer, provided)).buildEmail());
			}
			for (final Future<Email> result : workers.invokeAll(preparations)) {
				assertThat(result.get().getEmailToForward()).isSameAs(source);
			}
			assertThat(template.getEmailToForward()).isSameAs(source);
			assertThat(provided.getEmailToForward()).isNull();
			assertThat(source.contentReads.get()).isZero();
			assertThat(source.serializations.get()).isZero();
		} finally {
			workers.shutdownNow();
		}
	}

	@Test
	void rehearsalContainsTheSelectedForwardRatherThanTheSuppliedSource() throws Exception {
		final MimeMessage overrideSource = source("Override source");
		try (Mailer mailer = mailer(forward(source("Default source")).buildEmail(), forward(overrideSource).buildEmail())) {
			final byte[] rendered = mailer.rehearse(forward(source("Supplied source")).buildEmail()).getEmlBytes();
			final MimeMessage message = new MimeMessage(Session.getInstance(new Properties()), new ByteArrayInputStream(rendered));
			final Multipart content = (Multipart) message.getContent();

			assertThat(content.getCount()).isEqualTo(2);
			assertThat(content.getBodyPart(1).isMimeType("message/rfc822")).isTrue();
			final MimeMessage forwarded = (MimeMessage) content.getBodyPart(1).getContent();
			assertThat(forwarded.getSubject()).isEqualTo("Override source");
			assertThat(forwarded.getContent().toString()).contains("Body for Override source");
		}
	}

	@Test
	void exactEmlRehearsalStillBypassesForwardedTemplates() throws Exception {
		final byte[] bytes = ("From: sender@example.org\r\nTo: recipient@example.org\r\n"
				+ "Message-ID: <exact-forwarding@example.org>\r\nSubject: Exact source\r\n\r\nExact body\r\n")
				.getBytes(StandardCharsets.US_ASCII);
		final Email exact = mail.emailBuilder().startingFromExactEml(bytes).withEnvelopeRecipients("recipient@example.org").buildEmail();
		try (Mailer mailer = mailer(forward(source("Default source")).buildEmail(), forward(source("Override source")).buildEmail())) {
			final MailRehearsal rehearsal = mailer.rehearse(exact);
			assertThat(rehearsal.getEmlBytes()).containsExactly(bytes);
			assertThat(rehearsal.getEffectiveEmail().getEmailToForward()).isNull();
		}
	}

	private Mailer mailer(final Email defaults, final Email overrides) {
		return mail.mailerBuilder().withTransportModeLoggingOnly(true).withEmailDefaults(defaults).withEmailOverrides(overrides).buildMailer();
	}

	private Email govern(final Mailer mailer, final Email provided) {
		return mailer.getEmailGovernance().produceEmailApplyingDefaultsAndOverrides(provided);
	}

	private EmailPopulatingBuilder blank() {
		return mail.emailBuilder().startingBlank().fixingMessageId("<forward-governance@example.org>");
	}

	private EmailPopulatingBuilder forward(final MimeMessage source) {
		return mail.emailBuilder().forwarding(source).from("sender@example.org")
				.withRecipients(mail.recipientBuilder().to(null, "recipient@example.org")).withPlainText("Forwarded message follows.")
				.fixingMessageId("<forward-governance@example.org>");
	}

	private static MimeMessage source(final String subject) throws MessagingException {
		final MimeMessage source = new MimeMessage(Session.getInstance(new Properties()));
		source.setHeader("Message-ID", "<forwarded-source@example.org>");
		source.setSubject(subject);
		source.setText("Body for " + subject);
		return source;
	}

	private static final class ContentCountingMessage extends MimeMessage {
		private final AtomicInteger contentReads = new AtomicInteger();
		private final AtomicInteger serializations = new AtomicInteger();

		private ContentCountingMessage() {
			super(Session.getInstance(new Properties()));
		}

		@Override
		public Object getContent() throws IOException, MessagingException {
			contentReads.incrementAndGet();
			return super.getContent();
		}

		@Override
		public InputStream getInputStream() throws IOException, MessagingException {
			contentReads.incrementAndGet();
			return super.getInputStream();
		}

		@Override
		public void writeTo(final OutputStream output) throws IOException, MessagingException {
			serializations.incrementAndGet();
			super.writeTo(output);
		}
	}
}
