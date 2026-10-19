package org.simplejavamail.api.email;

import jakarta.mail.internet.MimeMessage;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.simplejavamail.api.SimpleJavaMail;
import org.simplejavamail.api.mailer.Mailer;
import org.simplejavamail.mailer.internal.EmailGovernanceImpl;
import testutil.ConfigLoaderTestHelper;

import java.util.List;
import java.util.Map;

import static java.nio.charset.StandardCharsets.US_ASCII;
import static org.assertj.core.api.Assertions.assertThat;
import static org.simplejavamail.api.email.EmailProperty.HEADERS;
import static org.simplejavamail.api.email.config.DeliveryStatusNotification.NotifyOption.FAILURE;

class EmailHeaderAndRecipientContractTest {

	private final SimpleJavaMail mail = SimpleJavaMail.withConfig(ConfigLoaderTestHelper.emptyConfig());

	@Test
	void replacingAHeaderIgnoresItsCapitalizationInTheFinalMessage() throws Exception {
		final Email email = message().withHeader("X-Route", "old").withHeader("x-route", "new", true).buildEmail();

		assertThat(email.getHeaders()).containsOnlyKeys("X-Route");
		assertThat(mail.converter().fromEmail(email).toMimeMessage().getHeader("X-ROUTE")).containsExactly("new");
	}

	@Test
	void additiveHeadersShareOneNameAndKeepDistinctValues() throws Exception {
		final Email email = message().withHeader("X-Route", "first")
				.withHeader("x-route", "second").withHeader("X-ROUTE", "first").withHeader("X-Unrelated", "kept").buildEmail();
		final MimeMessage mime = mail.converter().fromEmail(email).toMimeMessage();

		assertThat(email.getHeaders()).containsOnlyKeys("X-Route", "X-Unrelated");
		assertThat(mime.getHeader("x-route")).containsExactly("first", "second");
		assertThat(mime.getHeader("X-Unrelated")).containsExactly("kept");
	}

	@Test
	void bulkHeadersUseTheSameCaseInsensitiveIdentity() throws Exception {
		final Email email = message().withHeader("X-Route", "first")
				.withHeaders(Map.of("x-route", List.of("second", "third"))).buildEmail();

		assertThat(email.getHeaders()).containsOnlyKeys("X-Route");
		assertThat(mail.converter().fromEmail(email).toMimeMessage().getHeader("X-Route"))
				.containsExactly("first", "second", "third");
	}

	@Test
	void templatesReplaceWholeHeaderValuesRegardlessOfCapitalization() throws Exception {
		final Email defaults = message().withHeader("X-Route", "default").withHeader("X-Default", "kept").buildEmail();
		final Email provided = message().withHeader("x-route", "provided").buildEmail();
		final Email overrides = message().withHeader("X-ROUTE", "override one").withHeader("X-ROUTE", "override two").buildEmail();
		final Email effective = new EmailGovernanceImpl(null, defaults, overrides, null)
				.produceEmailApplyingDefaultsAndOverrides(provided);
		final MimeMessage mime = mail.converter().fromEmail(effective).toMimeMessage();

		assertThat(effective.getHeaders()).hasSize(2);
		assertThat(mime.getHeader("x-route")).containsExactly("override one", "override two");
		assertThat(mime.getHeader("X-Default")).containsExactly("kept");
		assertThat(provided.getHeaders().get("x-route")).containsExactly("provided");
	}

	@Test
	void explicitHeadersBeatDefaultsAndSuppressedOverridesStaySuppressed() throws Exception {
		final Email defaults = message().withHeader("X-Route", "default").withHeader("X-Default", "kept").buildEmail();
		final Email overrides = message().withHeader("X-ROUTE", "override").buildEmail();
		final Email provided = message().withHeader("x-route", "provided").dontApplyOverrideValueFor(HEADERS).buildEmail();
		final Email effective = new EmailGovernanceImpl(null, defaults, overrides, null)
				.produceEmailApplyingDefaultsAndOverrides(provided);

		assertThat(mail.converter().fromEmail(effective).toMimeMessage().getHeader("X-Route")).containsExactly("provided");
		assertThat(effective.getHeaders()).hasSize(2);
	}

	@Test
	void suppressingHeaderDefaultsRetainsProvidedAndUnrelatedOverrideHeaders() throws Exception {
		final Email defaults = message().withHeader("X-Route", "default").withHeader("X-Default", "removed").buildEmail();
		final Email overrides = message().withHeader("X-Other", "override").buildEmail();
		final Email provided = message().withHeader("x-route", "provided").dontApplyDefaultValueFor(HEADERS).buildEmail();
		final Email effective = new EmailGovernanceImpl(null, defaults, overrides, null)
				.produceEmailApplyingDefaultsAndOverrides(provided);

		assertThat(effective.getHeaders()).containsOnlyKeys("x-route", "X-Other");
		assertThat(mail.converter().fromEmail(effective).toMimeMessage().getHeader("X-Route")).containsExactly("provided");
	}

	@Test
	void exactMessageHeadersKeepTheirOriginalSpellingValuesAndBytes() throws Exception {
		final byte[] bytes = ("From: sender@example.org\r\nTo: recipient@example.org\r\nX-Route: first\r\n"
				+ "x-route: second\r\nSubject: Exact\r\n\r\nbody\r\n").getBytes(US_ASCII);
		final Email exact = mail.emailBuilder().startingFromExactEml(bytes)
				.withEnvelopeRecipients("recipient@example.org").buildEmail();
		final Email template = message().withHeader("X-ROUTE", "must not replace").buildEmail();
		try (Mailer mailer = mail.mailerBuilder().withSMTPServer("unused.example.org", 25)
				.withEmailDefaults(template).withEmailOverrides(template).buildMailer()) {
			assertThat(mailer.rehearse(exact).getEmlBytes()).containsExactly(bytes);
		}
	}

	@ParameterizedTest
	@ValueSource(strings = {"visible", "reply", "override"})
	void recipientEqualityAccountsForEveryOccurrenceInBothDirections(final String list) {
		final Recipient first = mail.recipientBuilder().to("First", "first@example.org");
		final Recipient second = mail.recipientBuilder().to("Second", "second@example.org");
		final Email distinct = withRecipients(list, List.of(first, second));
		final Email duplicated = withRecipients(list, List.of(first, first));

		assertThat(distinct.equals(duplicated)).isFalse();
		assertThat(duplicated.equals(distinct)).isFalse();
	}

	@ParameterizedTest
	@ValueSource(strings = {"visible", "reply", "override"})
	void recipientEqualityStillIgnoresOrderWhenOccurrencesMatch(final String list) {
		final Recipient first = mail.recipientBuilder().to("First", "first@example.org");
		final Recipient second = mail.recipientBuilder().to("Second", "second@example.org");
		final Email original = withRecipients(list, List.of(first, second, first));
		final Email reordered = withRecipients(list, List.of(first, first, second));

		assertThat(original).isEqualTo(reordered);
		assertThat(reordered).isEqualTo(original);
		assertThat(original.hashCode()).isEqualTo(reordered.hashCode());
	}

	@Test
	void equalityDoesNotCollapseRecipientsWithDifferentNotificationPreferences() {
		final Recipient ordinary = mail.recipientBuilder().to("Same", "same@example.org");
		final Recipient notified = new Recipient(ordinary.getName(), ordinary.getAddress(), ordinary.getType(), null, List.of(FAILURE));

		assertThat(withRecipients("visible", List.of(ordinary, notified)))
				.isNotEqualTo(withRecipients("visible", List.of(ordinary, ordinary)));
	}

	private EmailPopulatingBuilder message() {
		return mail.emailBuilder().startingBlank().from("sender@example.org")
				.withRecipients(mail.recipientBuilder().to("Recipient", "recipient@example.org"))
				.withPlainText("body");
	}

	private Email withRecipients(final String list, final List<Recipient> recipients) {
		final EmailPopulatingBuilder builder = mail.emailBuilder().startingBlank();
		switch (list) {
			case "visible": return builder.withRecipients(recipients).buildEmail();
			case "reply": return builder.withReplyTo(recipients).buildEmail();
			case "override": return builder.withOverrideReceivers(recipients).buildEmail();
			default: throw new IllegalArgumentException("Unknown recipient list: " + list);
		}
	}
}
