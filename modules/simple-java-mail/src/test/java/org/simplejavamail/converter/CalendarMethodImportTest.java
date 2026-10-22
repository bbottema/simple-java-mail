package org.simplejavamail.converter;

import jakarta.mail.Session;
import jakarta.mail.internet.MimeMessage;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.simplejavamail.api.SimpleJavaMail;
import org.simplejavamail.api.email.CalendarMethod;
import org.simplejavamail.api.email.Email;
import testutil.ConfigLoaderTestHelper;

import java.io.ByteArrayInputStream;
import java.util.Locale;
import java.util.Properties;

import static java.nio.charset.StandardCharsets.UTF_8;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class CalendarMethodImportTest {

	private final SimpleJavaMail mail = SimpleJavaMail.withConfig(ConfigLoaderTestHelper.emptyConfig());

	@ParameterizedTest
	@EnumSource(ImportRoute.class)
	void recognizesTheHeaderMethodAcrossPublicImportRoutes(final ImportRoute route) throws Exception {
		final String calendar = calendar("mEtHoD:cAnCeL");
		final Email imported = convert(route, message("text/calendar; charset=UTF-8; MeThOd = \"rEqUeSt\"", calendar));

		assertCalendar(imported, CalendarMethod.REQUEST, calendar);
	}

	@ParameterizedTest
	@EnumSource(ImportRoute.class)
	void recognizesTheBodyMethodAcrossPublicImportRoutes(final ImportRoute route) throws Exception {
		final String calendar = calendar("mEtHoD:rEqUeSt");
		final Email imported = convert(route, message("text/calendar; charset=UTF-8", calendar));

		assertCalendar(imported, CalendarMethod.REQUEST, calendar);
	}

	@ParameterizedTest
	@EnumSource(CalendarMethod.class)
	void recognizesEveryExistingMethodRegardlessOfCase(final CalendarMethod method) {
		final String lowerCase = method.name().toLowerCase(Locale.ROOT);
		final String calendar = calendar("method:" + lowerCase);

		assertCalendar(mail.converter().fromEml(message("text/calendar; charset=UTF-8; method=" + lowerCase, calendar)).toEmail(),
				method, calendar);
		assertCalendar(mail.converter().fromEml(message("text/calendar; charset=UTF-8", calendar)).toEmail(), method, calendar);
	}

	@ParameterizedTest
	@ValueSource(strings = {
			"text/calendar; charset=UTF-8; method=request",
			"text/calendar; charset=UTF-8; METHOD=ReQuEsT",
			"text/calendar; charset=UTF-8; method=\"request\"",
			"text/calendar; charset=UTF-8; method\t=\t\"rEqUeSt\"",
			"text/calendar; charset=UTF-8;\r\n\tMeThOd = \"request\"",
			"text/calendar; charset=UTF-8; method=(calendar action)\"request\""
	})
	void usesMimeParameterGrammarAndPreservesHeaderPriority(final String type) {
		final String calendar = calendar("METHOD:CANCEL");
		assertCalendar(mail.converter().fromEml(message(type, calendar)).toEmail(), CalendarMethod.REQUEST, calendar);
	}

	@Test
	void equivalentHeaderAndBodyValuesRetainTheOriginalCalendarText() {
		final String calendar = calendar("mEtHoD:rEqUeSt");
		assertCalendar(mail.converter().fromEml(message("text/calendar; charset=UTF-8; method=ReQuEsT", calendar)).toEmail(),
				CalendarMethod.REQUEST, calendar);
	}

	@Test
	void doesNotTreatAnotherParameterValueAsTheMethod() {
		final String calendar = calendar("METHOD:CANCEL");
		assertCalendar(mail.converter().fromEml(message("text/calendar; charset=UTF-8; name=\"method=REQUEST\"", calendar)).toEmail(),
				CalendarMethod.CANCEL, calendar);
	}

	@Test
	void doesNotTreatAnotherParameterNameAsTheMethod() {
		final String calendar = calendar("METHOD:CANCEL");
		assertCalendar(mail.converter().fromEml(message("text/calendar; charset=UTF-8; x-method=REQUEST", calendar)).toEmail(),
				CalendarMethod.CANCEL, calendar);
	}

	@Test
	void retainsCalendarTextAcrossMimeAndEmlRoundTrips() {
		final String calendar = calendar("method:request");
		Email imported = mail.converter().fromEml(message("text/calendar; charset=UTF-8; method=request", calendar)).toEmail();
		for (int round = 0; round < 3; round++) {
			assertCalendar(imported, CalendarMethod.REQUEST, calendar);
			imported = mail.converter().fromMimeMessage(mail.converter().fromEmail(imported).toMimeMessage()).toEmail();
			assertCalendar(imported, CalendarMethod.REQUEST, calendar);
			imported = mail.converter().fromEml(mail.converter().fromEmail(imported).toEml()).toEmail();
		}
		assertCalendar(imported, CalendarMethod.REQUEST, calendar);
	}

	@Test
	void interpretsMixedCaseCalendarWithoutChangingExactEmlBytes() {
		final String calendar = calendar("method:request");
		final byte[] original = message("text/calendar; charset=UTF-8; MeThOd=\"rEqUeSt\"", calendar).getBytes(UTF_8);
		final Email exact = mail.emailBuilder().startingFromExactEml(original).withEnvelopeRecipients("reader@example.org").buildEmail();

		assertCalendar(exact, CalendarMethod.REQUEST, calendar);
		assertThat(EmailConverter.emailToEMLByteArray(exact)).isEqualTo(original);
	}

	@Test
	void interpretsSignedCalendarWithoutChangingItsRetainedSourceBytes() {
		final String calendar = calendar("method:request");
		final byte[] original = ("From: sender@example.org\r\nTo: reader@example.org\r\nMIME-Version: 1.0\r\n"
				+ "Content-Type: multipart/signed; boundary=calendar-signature; protocol=\"application/pgp-signature\"\r\n\r\n"
				+ "--calendar-signature\r\nContent-Type: text/calendar; charset=UTF-8; MeThOd=\"rEqUeSt\"\r\n\r\n" + calendar
				+ "\r\n--calendar-signature\r\nContent-Type: application/pgp-signature\r\n\r\noriginal signature bytes\r\n"
				+ "--calendar-signature--\r\n").getBytes(UTF_8);

		final Email imported = mail.converter().fromEml(original).toEmail();
		assertThat(imported.getCalendarMethod()).isEqualTo(CalendarMethod.REQUEST);
		assertThat(imported.getCalendarText()).isEqualTo(calendar);
		assertThat(imported.getOriginalOpenPgpDetails().getOriginalProtectedMessage()).isEqualTo(original);
	}

	@Test
	void missingMethodsKeepTheirExistingFailure() {
		assertThatThrownBy(() -> mail.converter().fromEml(message("text/calendar; charset=UTF-8", calendar(null))).toEmail())
				.isInstanceOf(IllegalArgumentException.class).hasMessageContaining("Calendar METHOD not found");
	}

	@Test
	void unsupportedHeaderMethodsDoNotFallBackToARecognizedBodyMethod() {
		assertThatThrownBy(() -> mail.converter().fromEml(message("text/calendar; charset=UTF-8; method=XUNKNOWN",
				calendar("METHOD:REQUEST"))).toEmail()).isInstanceOf(IllegalArgumentException.class)
				.hasMessageContaining("CalendarMethod.XUNKNOWN");
	}

	@Test
	void unsupportedBodyMethodsKeepTheirExistingFailure() {
		assertThatThrownBy(() -> mail.converter().fromEml(message("text/calendar; charset=UTF-8", calendar("METHOD:XUNKNOWN"))).toEmail())
				.isInstanceOf(IllegalArgumentException.class).hasMessageContaining("CalendarMethod.XUNKNOWN");
	}

	@ParameterizedTest
	@ValueSource(strings = {"REQUEST extra", "REQUEST-extra", "request extra"})
	void retainsTheHistoricalLeadingMethodTokenForAGenuineParameter(final String method) {
		final String calendar = calendar("METHOD:CANCEL");
		assertCalendar(mail.converter().fromEml(message("text/calendar; charset=UTF-8; method=\"" + method + "\"", calendar)).toEmail(),
				CalendarMethod.REQUEST, calendar);
	}

	@ParameterizedTest
	@ValueSource(strings = {"method=REQUEST; method=CANCEL", "method=request; METHOD=cancel", "method=REQUEST; method=\"\""})
	void repeatedMethodParametersKeepTheFirstExistingMethod(final String parameters) {
		final String calendar = calendar("METHOD:CANCEL");
		assertCalendar(mail.converter().fromEml(message("text/calendar; charset=UTF-8; " + parameters, calendar)).toEmail(),
				CalendarMethod.REQUEST, calendar);
	}

	@Test
	void repeatedMethodParametersDoNotHideAnUnsupportedFirstMethod() {
		assertThatThrownBy(() -> mail.converter().fromEml(message("text/calendar; charset=UTF-8; method=XUNKNOWN; method=REQUEST",
				calendar("METHOD:REQUEST"))).toEmail()).isInstanceOf(IllegalArgumentException.class)
				.hasMessageContaining("CalendarMethod.XUNKNOWN");
	}

	private static void assertCalendar(final Email email, final CalendarMethod method, final String calendar) {
		assertThat(email.getCalendarMethod()).isEqualTo(method);
		assertThat(email.getCalendarText()).isEqualTo(calendar);
		assertThat(email.getAttachments()).isEmpty();
	}

	private Email convert(final ImportRoute route, final String eml) throws Exception {
		switch (route) {
			case STATIC_EML: return EmailConverter.emlToEmail(eml);
			case FLUENT_EML_EAGER: return mail.converter().fromEml(eml).withFetchAttachmentData(true).toEmail();
			case FLUENT_EML_LAZY: return mail.converter().fromEml(eml).withFetchAttachmentData(false).toEmail();
			default:
				final MimeMessage message = new MimeMessage(Session.getInstance(new Properties()), new ByteArrayInputStream(eml.getBytes(UTF_8)));
				final boolean eager = route == ImportRoute.STATIC_MIME_EAGER || route == ImportRoute.FLUENT_MIME_EAGER;
				return route == ImportRoute.STATIC_MIME_EAGER || route == ImportRoute.STATIC_MIME_LAZY
						? EmailConverter.mimeMessageToEmail(message, null, eager)
						: mail.converter().fromMimeMessage(message).withFetchAttachmentData(eager).toEmail();
		}
	}

	private static String message(final String type, final String calendar) {
		return "From: sender@example.org\r\nTo: reader@example.org\r\nSubject: Calendar method import\r\nMIME-Version: 1.0\r\n"
				+ "Content-Type: " + type + "\r\n\r\n" + calendar;
	}

	private static String calendar(final String methodProperty) {
		return "BEGIN:VCALENDAR\r\nVERSION:2.0\r\nPRODID:-//Example//Calendar//EN\r\n"
				+ (methodProperty == null ? "" : methodProperty + "\r\n")
				+ "BEGIN:VEVENT\r\nUID:meeting@example.org\r\nDTSTAMP:20261022T120000Z\r\nDTSTART:20261023T120000Z\r\n"
				+ "DTEND:20261023T130000Z\r\nORGANIZER:mailto:sender@example.org\r\nATTENDEE:mailto:reader@example.org\r\n"
				+ "SUMMARY:Keep My Case\r\nEND:VEVENT\r\nEND:VCALENDAR\r\n";
	}

	private enum ImportRoute {
		STATIC_EML, FLUENT_EML_EAGER, FLUENT_EML_LAZY,
		STATIC_MIME_EAGER, STATIC_MIME_LAZY, FLUENT_MIME_EAGER, FLUENT_MIME_LAZY
	}
}
